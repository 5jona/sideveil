package pochita.sideveil.host.panel

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import pochita.sideveil.host.R
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState

/**
 * Factory creating the locked-measure content [FrameLayout], `TYPE_APPLICATION_OVERLAY`
 * [Dialog], [PanelBackdropController], and [PanelMotionDriver] for [SidebarPanelWindow].
 */
internal object PanelDialogFactory {
    fun createContentContainer(
        themedContext: Context,
        cachedWidthProvider: () -> Int,
        cachedHeightProvider: () -> Int,
    ): FrameLayout =
        object : FrameLayout(themedContext) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                val cachedW = cachedWidthProvider()
                val cachedH = cachedHeightProvider()
                val wSpec =
                    if (MeasureSpec.getSize(widthMeasureSpec) <= 2 && cachedW > 2) {
                        MeasureSpec.makeMeasureSpec(cachedW, MeasureSpec.EXACTLY)
                    } else {
                        widthMeasureSpec
                    }
                val hSpec =
                    if (MeasureSpec.getSize(heightMeasureSpec) <= 2 && cachedH > 2) {
                        MeasureSpec.makeMeasureSpec(cachedH, MeasureSpec.EXACTLY)
                    } else {
                        heightMeasureSpec
                    }
                super.onMeasure(wSpec, hSpec)
            }
        }

    fun createBackdropController(
        context: Context,
        themedContext: Context,
        windowManager: WindowManager,
        contentContainer: FrameLayout,
        windowLayout: PanelWindowLayout,
        surfaceState: PanelSurfaceState,
        decorLocation: IntArray,
        scheduler: PanelOverlayScheduler,
        dialog: Dialog,
        onImeChangedWhileOpen: () -> Unit,
    ): PanelBackdropController {
        OverlayWindowFlags.preloadHardwareRenderer()
        val controller =
            PanelBackdropController(
                context = context,
                windowManager = windowManager,
                contentContainer = contentContainer,
                cornerRadiusProvider = { windowLayout.cornerPx(surfaceState.panelConfig) },
                windowProvider = { dialog.window },
            )
        dialog.window?.let(controller::attachToWindow)
        scheduler.bindWindowInsetsListener(
            contentContainer = contentContainer,
            themedContext = themedContext,
            windowManager = windowManager,
            windowProvider = { dialog.window },
            surfaceState = surfaceState,
            decorLocation = decorLocation,
            onImeChangedWhileOpen = onImeChangedWhileOpen,
        )
        return controller
    }

    fun createMotionDriver(
        contentContainer: FrameLayout,
        surfaceState: PanelSurfaceState,
        windowLayout: PanelWindowLayout,
        backdropController: PanelBackdropController,
        dialogProvider: () -> Dialog,
        contentMount: PanelContentMountController,
        stateMachine: PanelDragStateMachine,
        isBackIntercepted: () -> Boolean,
        onBackPressedIntercept: () -> Boolean,
        onCommitBackClose: () -> Unit,
        onRailMotionProgress: (Float) -> Unit,
    ): PanelMotionDriver =
        PanelMotionDriver(
            contentContainer = contentContainer,
            surfaceState = surfaceState,
            windowLayout = windowLayout,
            backdropController = backdropController,
            windowProvider = { dialogProvider().window },
            activeWebViewProvider = { contentMount.activeWebView },
            stateProvider = { stateMachine.currentState },
            isClosing = { stateMachine.closingInProgress },
            isBackIntercepted = isBackIntercepted,
            onBackPressedIntercept = onBackPressedIntercept,
            onCommitBackClose = onCommitBackClose,
            onRailMotionProgress = onRailMotionProgress,
            onContentAlphaChanged = contentMount::setContentAlpha,
        )

    fun createOverlayDialog(
        themedContext: Context,
        contentView: View,
        stateProvider: () -> PanelState,
        onBackPressedIntercept: () -> Boolean,
        onCloseRequested: () -> Unit,
        onOutsideTouchRequested: (Float, Float) -> Unit,
        onConfigureWindow: (Window) -> Unit,
    ): Dialog {
        val dialog =
            object : Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog) {
                override fun cancel() = onCloseRequested()

                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                    if (isBackUp && stateProvider() == PanelState.OPEN) {
                        if (!onBackPressedIntercept()) onCloseRequested()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && stateProvider() == PanelState.OPEN) {
                        onOutsideTouchRequested(event.rawX, event.rawY)
                        return true
                    }
                    return super.onTouchEvent(event)
                }
            }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(true)
        dialog.setContentView(
            contentView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val window = dialog.window ?: error("Overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        window.decorView.setPadding(0, 0, 0, 0)
        onConfigureWindow(window)
        return dialog
    }
}

/**
 * Android 16+ predictive-back [OnBackAnimationCallback] controller for [SidebarPanelWindow].
 * Drives live shrink, edge-shift, and progressive blur preview during back gestures when the
 * active plugin has not opted into back interception.
 */
internal class PanelBackAnimator(
    private val contentContainer: View,
    private val windowProvider: () -> Window?,
    private val backdropController: PanelBackdropController,
    private val windowLayout: PanelWindowLayout,
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    private val edgeSideProvider: () -> EdgeSide,
    private val isBackIntercepted: () -> Boolean,
    private val onBackPressedIntercept: () -> Boolean,
    private val onCommitClose: (Float) -> Unit,
) : OnBackAnimationCallback {
    private var predictiveProgress: Float = 0f
    private var registered = false

    fun register(dialog: Dialog) {
        if (registered) return
        registered =
            runCatching {
                dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this,
                )
                true
            }.getOrDefault(false)
    }

    fun unregister(dialog: Dialog) {
        if (!registered) return
        runCatching { dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(this) }
        registered = false
    }

    override fun onBackStarted(backEvent: BackEvent) {
        if (stateProvider() == PanelState.OPEN && !isBackIntercepted()) {
            predictiveProgress = 0f
        }
    }

    override fun onBackProgressed(backEvent: BackEvent) {
        if (stateProvider() != PanelState.OPEN || isClosing() || isBackIntercepted()) return
        val p = backEvent.progress.coerceIn(0f, 1f)
        predictiveProgress = p
        val scale = 1f - p * SCALE_DROP
        val shiftSign = if (edgeSideProvider() == EdgeSide.LEFT) -1f else 1f
        contentContainer.scaleX = scale
        contentContainer.scaleY = scale
        contentContainer.translationX = shiftSign * p * windowLayout.cachedWidthPx * SHIFT_FRACTION
        windowProvider()?.let { win ->
            val targetBlur = backdropController.targetBlurRadiusPx()
            val reducedBlur = PanelMotionChoreographer.computeProgressiveBlur(1f - p * 0.45f, targetBlur)
            backdropController.applyMotionFrame(win, reducedBlur, 1f - p * 0.12f, 1f - p * 0.2f, -p * 0.6f)
        }
    }

    override fun onBackCancelled() {
        predictiveProgress = 0f
        resetVisuals(animated = true)
    }

    override fun onBackInvoked() {
        val snapshot = predictiveProgress
        predictiveProgress = 0f
        resetVisuals(animated = false)
        if (stateProvider() == PanelState.OPEN && !onBackPressedIntercept()) {
            onCommitClose(snapshot)
        }
    }

    fun resetVisuals(animated: Boolean) {
        predictiveProgress = 0f
        if (animated) {
            contentContainer
                .animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(CANCEL_RESET_MS)
                .start()
            if (stateProvider() == PanelState.OPEN) {
                backdropController.apply()
            }
        } else {
            contentContainer.animate().cancel()
            contentContainer.scaleX = 1f
            contentContainer.scaleY = 1f
            contentContainer.translationX = 0f
        }
    }

    companion object {
        private const val SCALE_DROP = 0.07f
        private const val SHIFT_FRACTION = 0.08f
        private const val CANCEL_RESET_MS = 140L
    }
}
