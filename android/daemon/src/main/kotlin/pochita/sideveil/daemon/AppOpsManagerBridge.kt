package pochita.sideveil.daemon

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ServiceManager

data class HostAppPermissionsStatus(
    val installed: Boolean,
    val uid: Int?,
    val overlayGranted: Boolean,
)

/**
 * Ensures the headless Render Host APK holds OP_SYSTEM_ALERT_WINDOW and power-save exemption
 * without requiring interactive user prompts.
 */
class AppOpsManagerBridge(
    private val systemContext: Context,
    private val hostPackage: String,
) {
    private val appOpsService: Any? by lazy {
        runCatching {
            val binder = ServiceManager.getService("appops") ?: return@runCatching null
            val stubClass = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.onFailure { DaemonLog.warn("Failed to bind IAppOpsService", it) }.getOrNull()
    }

    private val deviceIdleService: Any? by lazy {
        runCatching {
            val binder = ServiceManager.getService("deviceidle") ?: return@runCatching null
            val stubClass = Class.forName("android.os.IDeviceIdleController\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.onFailure { DaemonLog.warn("Failed to bind IDeviceIdleController", it) }.getOrNull()
    }

    fun inspectAndEnsure(): HostAppPermissionsStatus {
        val uid = resolvePackageUid() ?: return HostAppPermissionsStatus(false, null, false)
        val granted = ensureOverlayPermission(uid)
        ensurePowerSaveWhitelist()
        return HostAppPermissionsStatus(installed = true, uid = uid, overlayGranted = granted)
    }

    fun resolvePackageUid(): Int? =
        runCatching {
            val pm = systemContext.packageManager
            pm.getPackageUid(hostPackage, PackageManager.PackageInfoFlags.of(0L))
        }.getOrNull()

    private fun ensureOverlayPermission(uid: Int): Boolean {
        val service = appOpsService ?: return false
        return runCatching {
            val checkMethod =
                service.javaClass.getMethod(
                    "checkOperation",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            val currentMode = checkMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage) as Int
            if (currentMode == MODE_ALLOWED) {
                return@runCatching true
            }
            val setModeMethod =
                service.javaClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            setModeMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage, MODE_ALLOWED)
            val verifyMode = checkMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage) as Int
            DaemonLog.info("Updated OP_SYSTEM_ALERT_WINDOW for $hostPackage (uid=$uid) -> mode=$verifyMode")
            verifyMode == MODE_ALLOWED
        }.onFailure {
            DaemonLog.warn("Failed to grant OP_SYSTEM_ALERT_WINDOW for $hostPackage", it)
        }.getOrDefault(false)
    }

    private fun ensurePowerSaveWhitelist() {
        val service = deviceIdleService ?: return
        runCatching {
            val isWhitelisted =
                service.javaClass
                    .getMethod("isPowerSaveWhitelistApp", String::class.java)
                    .invoke(service, hostPackage) as? Boolean
            if (isWhitelisted != true) {
                service.javaClass
                    .getMethod("addPowerSaveWhitelistApp", String::class.java)
                    .invoke(service, hostPackage)
                DaemonLog.info("Added $hostPackage to Doze power-save whitelist")
            }
        }.onFailure {
            DaemonLog.warn("DeviceIdle whitelist check failed", it)
        }
    }

    companion object {
        private const val OP_SYSTEM_ALERT_WINDOW = 24
        private const val MODE_ALLOWED = 0
    }
}
