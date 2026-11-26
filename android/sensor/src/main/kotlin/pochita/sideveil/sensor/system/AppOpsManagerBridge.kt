package pochita.sideveil.sensor.system

import android.app.AppOpsManager
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
 * Ensures the headless Render Host APK holds `OP_SYSTEM_ALERT_WINDOW`, background/FGS keepalive
 * AppOps (`RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`, `START_FOREGROUND`), Doze power-save
 * exemption, active standby bucket, and runtime permissions (`ACCESS_LOCAL_NETWORK`,
 * `POST_NOTIFICATIONS`) without requiring interactive user prompts.
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

    private val setUidModeMethod: Method? by lazy {
        appOpsService?.let { service ->
            runCatching {
                service.javaClass.getMethod(
                    "setUidMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
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

    private val usageStatsService: Any? by lazy {
        runCatching {
            val binder = ServiceManager.getService("usagestats") ?: return@runCatching null
            val stubClass = Class.forName("android.app.usage.IUsageStatsManager\$Stub")
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.onFailure { SensorLog.warn("Failed to bind IUsageStatsManager", it) }.getOrNull()
    }

    private val setStandbyBucketMethod: Method? by lazy {
        usageStatsService?.let { service ->
            runCatching {
                service.javaClass.getMethod(
                    "setAppStandbyBucket",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
        }
    }

    /** Inspects the Render Host package UID and grants overlay, keepalive, Doze, and runtime permissions. */
    fun inspectAndEnsure(): HostAppPermissionsStatus {
        val uid = resolvePackageUid() ?: return HostAppPermissionsStatus(false, null, false)
        val overlayOp = resolveOpCode(OPSTR_SYSTEM_ALERT_WINDOW, FALLBACK_OP_SYSTEM_ALERT_WINDOW)
        val granted = ensureAppOpAllowed(uid, overlayOp, OPSTR_SYSTEM_ALERT_WINDOW, alsoUidMode = false)
        ensureBackgroundKeepaliveOps(uid)
        ensurePowerSaveWhitelist()
        ensureActiveStandbyBucket()
        ensureRuntimePermission(PERM_ACCESS_LOCAL_NETWORK)
        ensureRuntimePermission(PERM_POST_NOTIFICATIONS)
        return HostAppPermissionsStatus(installed = true, uid = uid, overlayGranted = granted)
    }

    /** Resolves the installed Linux UID of [hostPackage], or `null` if not installed. */
    fun resolvePackageUid(): Int? =
        runCatching {
            val pm = systemContext.packageManager
            pm.getPackageUid(hostPackage, PackageManager.PackageInfoFlags.of(0L))
        }.getOrNull()

    private fun ensureBackgroundKeepaliveOps(uid: Int) {
        for (opName in KEEPALIVE_OP_NAMES) {
            val opCode = resolveOpCode(opName, -1)
            if (opCode >= 0) {
                ensureAppOpAllowed(uid, opCode, opName, alsoUidMode = true)
            }
        }
    }

    private fun ensureAppOpAllowed(
        uid: Int,
        opCode: Int,
        opLabel: String,
        alsoUidMode: Boolean,
    ): Boolean {
        val service = appOpsService ?: return false
        val checkMethod = checkOperationMethod ?: return false
        return runCatching {
            val currentMode = checkMethod.invoke(service, opCode, uid, hostPackage) as Int
            if (currentMode == MODE_ALLOWED) {
                return@runCatching true
            }
            val setMethod = setModeMethod ?: return@runCatching false
            setMethod.invoke(service, opCode, uid, hostPackage, MODE_ALLOWED)
            if (alsoUidMode) {
                runCatching { setUidModeMethod?.invoke(service, opCode, uid, MODE_ALLOWED) }
            }
            val verifyMode = checkMethod.invoke(service, opCode, uid, hostPackage) as Int
            SensorLog.info("Updated $opLabel (op=$opCode) for $hostPackage (uid=$uid) -> mode=$verifyMode")
            verifyMode == MODE_ALLOWED
        }.onFailure {
            SensorLog.warn("Failed to grant $opLabel for $hostPackage", it)
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

    private fun ensureActiveStandbyBucket() {
        val service = usageStatsService ?: return
        val method = setStandbyBucketMethod ?: return
        runCatching {
            method.invoke(service, hostPackage, STANDBY_BUCKET_ACTIVE, USER_SYSTEM)
        }.onFailure {
            SensorLog.warn("Failed setting active standby bucket for $hostPackage", it)
        }
    }

    private fun ensureRuntimePermission(permission: String) {
        val pm = systemContext.packageManager
        val permissionDefined =
            runCatching {
                pm.getPermissionInfo(permission, 0)
                true
            }.getOrDefault(false)
        if (!permissionDefined) return
        if (pm.checkPermission(permission, hostPackage) == PackageManager.PERMISSION_GRANTED) {
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
            grantMethod.invoke(pm, hostPackage, permission, Process.myUserHandle())
            SensorLog.info("Granted $permission to $hostPackage")
        }.onFailure {
            SensorLog.warn("Failed to grant $permission to $hostPackage", it)
        }
    }

    private fun resolveOpCode(
        opName: String,
        fallback: Int,
    ): Int =
        runCatching {
            val method = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
            (method.invoke(null, opName) as? Int) ?: fallback
        }.getOrDefault(fallback)

    companion object {
        private const val FALLBACK_OP_SYSTEM_ALERT_WINDOW = 24
        private const val MODE_ALLOWED = 0
        private const val STANDBY_BUCKET_ACTIVE = 10
        private const val USER_SYSTEM = 0
        private const val OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window"
        private val KEEPALIVE_OP_NAMES =
            listOf(
                "android:run_in_background",
                "android:run_any_in_background",
                "android:start_foreground",
            )
        private const val PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val PERM_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
