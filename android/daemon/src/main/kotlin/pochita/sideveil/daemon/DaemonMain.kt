package pochita.sideveil.daemon

import android.app.ActivityThread
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.android.internal.os.ApplicationSharedMemory
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.shared.AppearancePatchParams
import pochita.sideveil.shared.ConfigUpdatedPayload
import pochita.sideveil.shared.DaemonStatus
import pochita.sideveil.shared.EdgeSide
import pochita.sideveil.shared.HostHelloPayload
import pochita.sideveil.shared.PanelCommandPayload
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.ScopeChangedPayload
import pochita.sideveil.shared.SideveilEvents
import pochita.sideveil.shared.SideveilWireJson
import java.io.File

private data class DaemonArgs(
    val configFile: File,
    val runDir: File,
    val hostPackage: String,
)

/**
 * Privileged UID 1000 app_process daemon coordinating foreground scope detection,
 * edge gesture pilfering, loopback IPC, and HostService supervision.
 */
object DaemonMain {
    private val startedAtElapsedMs = SystemClock.elapsedRealtime()

    @Volatile
    private var hostPid: Int? = null

    @Volatile
    private var crossWindowBlurSupported: Boolean? = null

    @Volatile
    private var panelState: PanelState = PanelState.CLOSED

    @JvmStatic
    fun main(args: Array<String>) {
        initApplicationSharedMemory()
        Looper.prepareMainLooper()

        val parsed = parseArgs(args)
        DaemonLog.info(
            "Starting Sideveil daemon (uid=${Process.myUid()}, pid=${Process.myPid()}, " +
                "config=${parsed.configFile.absolutePath}, runDir=${parsed.runDir.absolutePath})",
        )

        val systemContext: Context = ActivityThread.systemMain().systemUiContext

        hostPid = null
        crossWindowBlurSupported = null
        panelState = PanelState.CLOSED

        lateinit var configStore: ConfigStore
        lateinit var foregroundMonitor: ForegroundMonitor
        lateinit var edgeMonitor: EdgeGestureMonitor
        lateinit var controlServer: ControlServer
        lateinit var hostSupervisor: HostSupervisor

        fun computeScopeActive(): Boolean {
            val cfg = configStore.current
            return cfg.matchesPackage(foregroundMonitor.foregroundPackage)
        }

        fun pushConfigSnapshot() {
            val active = computeScopeActive()
            val cfg = configStore.current
            edgeMonitor.updateState(cfg, active, panelState)
            controlServer.emitEvent(
                SideveilEvents.CONFIG_UPDATED,
                SideveilWireJson.encodeToJsonElement(
                    ConfigUpdatedPayload(
                        config = cfg,
                        scopeActive = active,
                        foregroundPackage = foregroundMonitor.foregroundPackage,
                    ),
                ),
            )
        }

        val rpcDelegate =
            object : DaemonRpcDelegate {
                override fun buildStatus(): DaemonStatus {
                    val cfg = configStore.current
                    val perms = hostSupervisor.refreshPermissionsOnly()
                    val geo = edgeMonitor.geometry
                    return DaemonStatus(
                        pid = Process.myPid(),
                        uid = Process.myUid(),
                        uptimeMs = SystemClock.elapsedRealtime() - startedAtElapsedMs,
                        configVersion = cfg.version,
                        enabled = cfg.enabled,
                        scopeActive = computeScopeActive(),
                        foregroundPackage = foregroundMonitor.foregroundPackage,
                        hostPackage = parsed.hostPackage,
                        hostConnected = controlServer.isHostConnected(),
                        hostPid = hostPid,
                        overlayPermissionGranted = perms.overlayGranted,
                        crossWindowBlurSupported = crossWindowBlurSupported,
                        gestureMonitorActive = edgeMonitor.gestureMonitorActive,
                        taskListenerActive = foregroundMonitor.taskListenerActive,
                        panelState = panelState,
                        screenWidthPx = geo.widthPx,
                        screenHeightPx = geo.heightPx,
                        density = geo.density,
                        wsPort = controlServer.boundPort,
                    )
                }

                override fun currentConfigJson(): JsonElement = SideveilWireJson.encodeToJsonElement(configStore.current)

                override fun reloadConfig(): JsonElement {
                    val reloaded = configStore.reloadFromDisk(notifyIfUnchanged = true)
                    return SideveilWireJson.encodeToJsonElement(reloaded)
                }

                override fun patchAppearance(params: AppearancePatchParams): JsonElement {
                    val updated = configStore.updateAppearance(params)
                    return SideveilWireJson.encodeToJsonElement(updated)
                }

                override fun sendPanelCommand(command: String) {
                    controlServer.emitEvent(
                        SideveilEvents.PANEL_COMMAND,
                        SideveilWireJson.encodeToJsonElement(PanelCommandPayload(command)),
                    )
                }

                override fun restartHost() {
                    hostSupervisor.forceRestart()
                }

                override fun onHostHello(payload: HostHelloPayload) {
                    hostPid = payload.pid
                    crossWindowBlurSupported = payload.crossWindowBlurSupported
                    DaemonLog.info("Host hello pid=${payload.pid} blurSupported=${payload.crossWindowBlurSupported}")
                }

                override fun onPanelStateChanged(state: PanelState) {
                    if (panelState != state) {
                        panelState = state
                        edgeMonitor.updateState(configStore.current, computeScopeActive(), panelState)
                    }
                }
            }

        controlServer =
            ControlServer(
                runDir = parsed.runDir,
                uptimeProvider = { SystemClock.elapsedRealtime() - startedAtElapsedMs },
                delegate = rpcDelegate,
            )

        edgeMonitor =
            EdgeGestureMonitor(
                systemContext = systemContext,
                listener =
                    object : EdgeGestureListener {
                        override fun onDragStart(
                            side: EdgeSide,
                            startYFraction: Float,
                        ) {
                            panelState = PanelState.DRAGGING
                            controlServer.emitEvent(
                                SideveilEvents.GESTURE_DRAG_START,
                                SideveilWireJson.encodeToJsonElement(
                                    pochita.sideveil.shared.GestureDragStartPayload(side, startYFraction),
                                ),
                            )
                        }

                        override fun onDragMove(
                            side: EdgeSide,
                            progress: Float,
                            rawX: Float,
                            rawY: Float,
                            deltaX: Float,
                        ) {
                            controlServer.emitEvent(
                                SideveilEvents.GESTURE_DRAG_MOVE,
                                SideveilWireJson.encodeToJsonElement(
                                    pochita.sideveil.shared.GestureDragMovePayload(side, progress, rawX, rawY, deltaX),
                                ),
                            )
                        }

                        override fun onDragEnd(
                            shouldOpen: Boolean,
                            velocityPxPerSec: Float,
                        ) {
                            controlServer.emitEvent(
                                SideveilEvents.GESTURE_DRAG_END,
                                SideveilWireJson.encodeToJsonElement(
                                    pochita.sideveil.shared.GestureDragEndPayload(shouldOpen, velocityPxPerSec),
                                ),
                            )
                        }

                        override fun onDisplayChanged(geometry: DisplayGeometry) {
                            pushConfigSnapshot()
                        }
                    },
            )

        val monitorThread =
            HandlerThread("SideveilMonitorWorker", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
            }
        val monitorHandler = Handler(monitorThread.looper)

        foregroundMonitor =
            ForegroundMonitor(
                hostPackage = parsed.hostPackage,
                onForegroundChanged = { pkg ->
                    val active = configStore.current.matchesPackage(pkg)
                    edgeMonitor.updateState(configStore.current, active, panelState)
                    controlServer.emitEvent(
                        SideveilEvents.SCOPE_CHANGED,
                        SideveilWireJson.encodeToJsonElement(ScopeChangedPayload(pkg, active)),
                    )
                },
                handler = monitorHandler,
            )

        configStore =
            ConfigStore(
                configFile = parsed.configFile,
                onChanged = { _ -> pushConfigSnapshot() },
            )

        val appOpsBridge = AppOpsManagerBridge(systemContext, parsed.hostPackage)
        hostSupervisor =
            HostSupervisor(
                hostPackage = parsed.hostPackage,
                appOpsBridge = appOpsBridge,
                controlServer = controlServer,
                handler = monitorHandler,
            )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                DaemonLog.info("Shutting down Sideveil daemon")
                hostSupervisor.stop()
                edgeMonitor.stop()
                foregroundMonitor.stop()
                configStore.stop()
                controlServer.stop()
                runCatching { monitorThread.quitSafely() }
            },
        )

        configStore.start()
        controlServer.start()
        foregroundMonitor.start()
        edgeMonitor.start(configStore.current)
        edgeMonitor.updateState(configStore.current, computeScopeActive(), panelState)
        hostSupervisor.start()

        Looper.loop()
    }

    private fun initApplicationSharedMemory() {
        runCatching {
            val shm = ApplicationSharedMemory.create()
            ApplicationSharedMemory.setInstance(shm)
        }.onFailure { err ->
            DaemonLog.warn("ApplicationSharedMemory initialization skipped or failed", err)
        }
    }

    private fun parseArgs(args: Array<String>): DaemonArgs {
        var configPath = "/data/system/sideveil/config.json"
        var runDirPath = "/data/system/sideveil/run"
        var hostPkg = "pochita.sideveil.host"

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--config" -> if (i + 1 < args.size) configPath = args[++i]
                "--run-dir" -> if (i + 1 < args.size) runDirPath = args[++i]
                "--host-pkg" -> if (i + 1 < args.size) hostPkg = args[++i]
            }
            i++
        }
        return DaemonArgs(
            configFile = File(configPath),
            runDir = File(runDirPath),
            hostPackage = hostPkg,
        )
    }
}
