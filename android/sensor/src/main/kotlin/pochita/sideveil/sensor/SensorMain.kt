package pochita.sideveil.sensor

import android.app.ActivityThread
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.android.internal.os.ApplicationSharedMemory
import pochita.sideveil.protocol.DebugGestureStats
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.HubToSensor
import pochita.sideveil.protocol.SensorToHub
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.sensor.gesture.EdgeGestureListener
import pochita.sideveil.sensor.gesture.EdgeGestureMonitor
import pochita.sideveil.sensor.ipc.HubUdsClient
import pochita.sideveil.sensor.system.AppOpsManagerBridge
import pochita.sideveil.sensor.system.ForegroundMonitor
import pochita.sideveil.sensor.system.NetworkContextWatcher

private data class SensorArgs(
    val socketPath: String,
    val hostPackage: String,
)

/**
 * Thin UID 1000 Android Framework sensor spawned directly by Rust `sideveild`.
 *
 * Restricted strictly to operations requiring `UID 1000` Java/Hidden APIs:
 * foreground Activity tracking, `monitorGestureInput` + `pilferPointers`, and AppOps grants.
 */
object SensorMain {
    private const val SENSOR_VERSION = "0.2.0"
    private const val DEFAULT_SOCKET_PATH = "/data/system/sideveil/run/hub.sock"
    private const val DEFAULT_HOST_PACKAGE = "pochita.sideveil.host"

    /** Entrypoint invoked via `app_process64` under `UID 1000`. */
    @JvmStatic
    fun main(args: Array<String>) {
        initApplicationSharedMemory()
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()

        val parsed = parseArgs(args)
        SensorLog.info(
            "Starting SideVeil sensor (uid=${Process.myUid()}, pid=${Process.myPid()}, " +
                "socket=${parsed.socketPath}, hostPkg=${parsed.hostPackage})",
        )

        val systemContext: Context = ActivityThread.systemMain().systemUiContext
        val workerThread =
            HandlerThread("SideVeilSensorWorker", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
            }
        val workerHandler = Handler(workerThread.looper)

        val appOpsBridge = AppOpsManagerBridge(systemContext, parsed.hostPackage)
        val initialPerms = appOpsBridge.inspectAndEnsure()

        lateinit var hubClient: HubUdsClient
        lateinit var foregroundMonitor: ForegroundMonitor
        lateinit var edgeMonitor: EdgeGestureMonitor
        lateinit var networkWatcher: NetworkContextWatcher

        edgeMonitor = createEdgeMonitor(systemContext) { hubClient }
        foregroundMonitor =
            ForegroundMonitor(
                hostPackage = parsed.hostPackage,
                handler = workerHandler,
                onForegroundChanged = { pkg, act ->
                    hubClient.send(SensorToHub.ForegroundChanged(packageName = pkg, activityName = act))
                },
            )
        networkWatcher =
            NetworkContextWatcher(
                systemContext = systemContext,
                handler = workerHandler,
                onContextChanged = { snapshot ->
                    hubClient.send(SensorToHub.NetworkContextChanged(context = snapshot))
                },
            )

        hubClient =
            HubUdsClient(
                socketPath = parsed.socketPath,
                version = SENSOR_VERSION,
                onConnected = {
                    val perms = appOpsBridge.inspectAndEnsure()
                    hubClient.send(
                        SensorToHub.Hello(
                            pid = Process.myPid(),
                            version = SENSOR_VERSION,
                            display = edgeMonitor.geometry,
                            taskListenerActive = foregroundMonitor.taskListenerActive,
                            gestureMonitorActive = edgeMonitor.gestureMonitorActive,
                            overlayPermissionGranted = perms.overlayGranted,
                            foregroundPackage = foregroundMonitor.foregroundPackage,
                            foregroundActivity = foregroundMonitor.foregroundActivity,
                        ),
                    )
                    hubClient.send(SensorToHub.NetworkContextChanged(context = networkWatcher.snapshot))
                    hubClient.send(SensorToHub.GestureStatsUpdated(stats = edgeMonitor.gestureStatsSnapshot()))
                },
                onMessage = { msg ->
                    handleHubMessage(msg, edgeMonitor, appOpsBridge, workerHandler, hubClient)
                },
            )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                SensorLog.info("Shutting down SideVeil sensor")
                hubClient.stop()
                networkWatcher.stop()
                edgeMonitor.stop()
                foregroundMonitor.stop()
                runCatching { workerThread.quitSafely() }
            },
        )

        SensorLog.info("Initial host permissions: overlayGranted=${initialPerms.overlayGranted}")
        foregroundMonitor.start()
        edgeMonitor.start(SideVeilConfig.DEFAULT)
        networkWatcher.start()
        hubClient.start()

        Looper.loop()
    }

    private fun createEdgeMonitor(
        systemContext: Context,
        clientProvider: () -> HubUdsClient,
    ): EdgeGestureMonitor =
        EdgeGestureMonitor(
            systemContext = systemContext,
            listener =
                object : EdgeGestureListener {
                    override fun onDragStart(
                        side: EdgeSide,
                        startYFraction: Float,
                    ) {
                        clientProvider().send(SensorToHub.GestureDragStart(side, startYFraction))
                    }

                    override fun onDragMove(
                        side: EdgeSide,
                        progress: Float,
                        rawX: Float,
                        rawY: Float,
                        deltaX: Float,
                    ) {
                        clientProvider().send(SensorToHub.GestureDragMove(side, progress, rawX, rawY, deltaX))
                    }

                    override fun onDragEnd(
                        shouldOpen: Boolean,
                        velocityPxPerSec: Float,
                        canceled: Boolean,
                    ) {
                        clientProvider().send(SensorToHub.GestureDragEnd(shouldOpen, velocityPxPerSec, canceled))
                    }

                    override fun onFullscreenHint(side: EdgeSide) {
                        clientProvider().send(SensorToHub.GestureFullscreenHint(side))
                    }

                    override fun onEdgeRepositionStart(
                        side: EdgeSide,
                        topPercent: Int,
                    ) {
                        clientProvider().send(SensorToHub.EdgeRepositionStart(side, topPercent))
                    }

                    override fun onEdgeRepositionMove(
                        side: EdgeSide,
                        topPercent: Int,
                    ) {
                        clientProvider().send(SensorToHub.EdgeRepositionMove(side, topPercent))
                    }

                    override fun onEdgeRepositionEnd(
                        side: EdgeSide,
                        topPercent: Int,
                        committed: Boolean,
                    ) {
                        clientProvider().send(SensorToHub.EdgeRepositionEnd(side, topPercent, committed))
                    }

                    override fun onDisplayChanged(geometry: DisplayGeometry) {
                        clientProvider().send(SensorToHub.DisplayChanged(geometry))
                    }

                    override fun onGestureStatsUpdated(stats: DebugGestureStats) {
                        clientProvider().send(SensorToHub.GestureStatsUpdated(stats))
                    }

                    override fun onStatusBarPullDown() {
                        clientProvider().send(SensorToHub.StatusBarPullDown)
                    }
                },
        )

    private fun handleHubMessage(
        message: HubToSensor,
        edgeMonitor: EdgeGestureMonitor,
        appOpsBridge: AppOpsManagerBridge,
        workerHandler: Handler,
        hubClient: HubUdsClient,
    ) {
        when (message) {
            is HubToSensor.SyncState -> {
                edgeMonitor.updateState(
                    nextConfig = message.config,
                    nextScopeActive = message.scopeActive,
                    nextPanelState = message.panelState,
                    nextFullscreenActive = message.fullscreenActive,
                )
            }

            HubToSensor.RefreshPermissions -> {
                workerHandler.post {
                    val status = appOpsBridge.inspectAndEnsure()
                    hubClient.send(
                        SensorToHub.PermissionsStatus(
                            installed = status.installed,
                            uid = status.uid,
                            overlayGranted = status.overlayGranted,
                        ),
                    )
                }
            }

            is HubToSensor.Ping -> {
                hubClient.send(SensorToHub.Pong(seq = message.seq))
            }
        }
    }

    private fun initApplicationSharedMemory() {
        runCatching {
            val shm = ApplicationSharedMemory.create()
            ApplicationSharedMemory.setInstance(shm)
        }.onFailure { err ->
            SensorLog.warn("ApplicationSharedMemory initialization skipped or failed", err)
        }
    }

    private fun parseArgs(args: Array<String>): SensorArgs {
        var socketPath = DEFAULT_SOCKET_PATH
        var hostPkg = DEFAULT_HOST_PACKAGE
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--socket" -> if (i + 1 < args.size) socketPath = args[++i]
                "--host-pkg" -> if (i + 1 < args.size) hostPkg = args[++i]
            }
            i++
        }
        return SensorArgs(socketPath = socketPath, hostPackage = hostPkg)
    }
}
