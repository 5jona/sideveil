package pochita.sideveil.daemon

import android.os.Handler
import android.os.Looper

/**
 * Supervises pochita.sideveil.host permissions and lifecycle state from UID 1000.
 * Keeping OP_SYSTEM_ALERT_WINDOW and Doze exemptions active enables root launch
 * helpers (service.sh / sideveil-ctl) to start HostService cleanly under ColorOS startup rules.
 */
class HostSupervisor(
    private val hostPackage: String,
    private val appOpsBridge: AppOpsManagerBridge,
    private val controlServer: ControlServer,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    @Volatile
    var lastPermissionStatus: HostAppPermissionsStatus = HostAppPermissionsStatus(false, null, false)
        private set

    private var running = false
    private var missingLogged = false

    private val watchdogRunnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                tick()
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }

    fun start() {
        running = true
        tick()
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    fun forceRestart() {
        DaemonLog.info("Host service restart requested")
        controlServer.closeActiveHostSession("Forced host restart")
        runCatching {
            Runtime.getRuntime().exec(arrayOf("/system/bin/am", "force-stop", hostPackage)).waitFor()
        }
    }

    fun refreshPermissionsOnly(): HostAppPermissionsStatus {
        lastPermissionStatus = appOpsBridge.inspectAndEnsure()
        return lastPermissionStatus
    }

    fun stop() {
        running = false
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun tick() {
        val perms = refreshPermissionsOnly()
        if (!perms.installed) {
            if (!missingLogged) {
                missingLogged = true
                DaemonLog.warn("Render Host package $hostPackage is not installed yet")
            }
            return
        }
        missingLogged = false
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 10_000L
    }
}
