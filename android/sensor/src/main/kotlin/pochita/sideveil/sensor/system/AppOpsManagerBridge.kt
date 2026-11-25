package pochita.sideveil.sensor.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.os.UserHandle
import pochita.sideveil.sensor.SensorLog
import java.lang.reflect.Method

/**
 * Snapshot of installation and permission grant status for the Render Host APK.
 */
data class HostAppPermissionsStatus(
    val installed: Boolean,
    val uid: Int?,
    val overlayGranted: Boolean,
)

/**
 * Ensures the headless Render Host APK holds `OP_SYSTEM_ALERT_WINDOW` and Doze power-save
 * exemption without requiring interactive user prompts.
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
        }.onFailure { SensorLog.warn("Failed to bind IAppOpsService", it) }.getOrNull()
    }

    private val checkOperationMethod: Method? by lazy {
        appOpsService?.let { service ->
            runCatching {
                service.javaClass.getMethod(
                    "checkOperation",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            }.getOrNull()
        }
    }

    private val setModeMethod: Method? by lazy {
        appOpsService?.let { service ->
            runCatching {
                service.javaClass.getMethod(
                    "setMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        }
    }

    private val deviceIdleService: Any? by lazy {
        runCatching {
            val binder = ServiceManager.getService("deviceidle") ?: return@runCatching null
            val stubClass = Class.forName("android.os.IDeviceIdleController\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.onFailure { SensorLog.warn("Failed to bind IDeviceIdleController", it) }.getOrNull()
    }

    private val isPowerSaveWhitelistMethod: Method? by lazy {
        deviceIdleService?.let { service ->
            runCatching {
                service.javaClass.getMethod("isPowerSaveWhitelistApp", String::class.java)
            }.getOrNull()
        }
    }

    private val addPowerSaveWhitelistMethod: Method? by lazy {
        deviceIdleService?.let { service ->
            runCatching {
                service.javaClass.getMethod("addPowerSaveWhitelistApp", String::class.java)
            }.getOrNull()
        }
    }

    /** Inspects the Render Host package UID and grants overlay, Doze, and LAN permissions. */
    fun inspectAndEnsure(): HostAppPermissionsStatus {
        val uid = resolvePackageUid() ?: return HostAppPermissionsStatus(false, null, false)
        val granted = ensureOverlayPermission(uid)
        ensurePowerSaveWhitelist()
        ensureLocalNetworkPermission()
        return HostAppPermissionsStatus(installed = true, uid = uid, overlayGranted = granted)
    }

    /** Resolves the installed Linux UID of [hostPackage], or `null` if not installed. */
    fun resolvePackageUid(): Int? =
        runCatching {
            val pm = systemContext.packageManager
            pm.getPackageUid(hostPackage, PackageManager.PackageInfoFlags.of(0L))
        }.getOrNull()

    private fun ensureOverlayPermission(uid: Int): Boolean {
        val service = appOpsService ?: return false
        val checkMethod = checkOperationMethod ?: return false
        return runCatching {
            val currentMode = checkMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage) as Int
            if (currentMode == MODE_ALLOWED) {
                return@runCatching true
            }
            val setMethod = setModeMethod ?: return@runCatching false
            setMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage, MODE_ALLOWED)
            val verifyMode = checkMethod.invoke(service, OP_SYSTEM_ALERT_WINDOW, uid, hostPackage) as Int
            SensorLog.info("Updated OP_SYSTEM_ALERT_WINDOW for $hostPackage (uid=$uid) -> mode=$verifyMode")
            verifyMode == MODE_ALLOWED
        }.onFailure {
            SensorLog.warn("Failed to grant OP_SYSTEM_ALERT_WINDOW for $hostPackage", it)
        }.getOrDefault(false)
    }

    private fun ensurePowerSaveWhitelist() {
        val service = deviceIdleService ?: return
        val checkMethod = isPowerSaveWhitelistMethod ?: return
        runCatching {
            val isWhitelisted = checkMethod.invoke(service, hostPackage) as? Boolean
            if (isWhitelisted != true) {
                addPowerSaveWhitelistMethod?.invoke(service, hostPackage)
                SensorLog.info("Added $hostPackage to Doze power-save whitelist")
            }
        }.onFailure {
            SensorLog.warn("DeviceIdle whitelist check failed", it)
        }
    }

    private fun ensureLocalNetworkPermission() {
        val pm = systemContext.packageManager
        val permissionDefined =
            runCatching {
                pm.getPermissionInfo(PERM_ACCESS_LOCAL_NETWORK, 0)
                true
            }.getOrDefault(false)
        if (!permissionDefined) return
        if (pm.checkPermission(PERM_ACCESS_LOCAL_NETWORK, hostPackage) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            val grantMethod =
                pm.javaClass.getMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    UserHandle::class.java,
                )
            grantMethod.invoke(pm, hostPackage, PERM_ACCESS_LOCAL_NETWORK, Process.myUserHandle())
            SensorLog.info("Granted $PERM_ACCESS_LOCAL_NETWORK to $hostPackage")
        }.onFailure {
            SensorLog.warn("Failed to grant $PERM_ACCESS_LOCAL_NETWORK to $hostPackage", it)
        }
    }

    companion object {
        private const val OP_SYSTEM_ALERT_WINDOW = 24
        private const val MODE_ALLOWED = 0
        private const val PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
