package pochita.sideveil.sensor

import android.app.ActivityManager
import android.content.ComponentName
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Monitors foreground Activity package changes via Android 16-17 ITaskStackListener
 * paired with a low-frequency reconciliation poll and optional ColorOS focus lookup.
 */
class ForegroundMonitor(
    private val hostPackage: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onForegroundChanged: (packageName: String, activityName: String) -> Unit,
) {
    @Volatile
    var foregroundPackage: String = ""
        private set

    @Volatile
    var foregroundActivity: String = ""
        private set

    @Volatile
    var taskListenerActive: Boolean = false
        private set

    private var atmService: Any? = null
    private var getTasksMethod: Method? = null
    private var registeredListener: Any? = null

    private val oplusFocusCaller: (() -> String?)? by lazy {
        runCatching {
            val cls = Class.forName("android.view.OplusWindowManager")
            val instance = cls.getDeclaredConstructor().newInstance()
            val method = cls.getMethod("getCurrentFocus")
            ({ method.invoke(instance) as? String })
        }.getOrNull()
    }

    @Volatile
    private var running = false

    private val debouncedRefresh = Runnable { refreshNow() }
    private val periodicPoll =
        object : Runnable {
            override fun run() {
                if (!running) return
                refreshNow()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    fun start() {
        running = true
        registerTaskListener()
        refreshNow()
        handler.postDelayed(periodicPoll, POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(debouncedRefresh)
        handler.removeCallbacks(periodicPoll)
        val atm = atmService
        val listener = registeredListener
        if (atm != null && listener != null) {
            runCatching {
                val iface = Class.forName("android.app.ITaskStackListener")
                atm.javaClass.getMethod("unregisterTaskStackListener", iface).invoke(atm, listener)
            }.onFailure { SensorLog.warn("Failed to unregister task stack listener", it) }
        }
        registeredListener = null
        taskListenerActive = false
    }

    fun refreshNow(): String {
        val (pkg, act) = queryTopComponent()
        if (pkg.isNotBlank() && (pkg != foregroundPackage || act != foregroundActivity)) {
            foregroundPackage = pkg
            foregroundActivity = act
            SensorLog.info("Foreground changed -> $pkg/$act")
            onForegroundChanged(pkg, act)
        }
        return foregroundPackage
    }

    private fun registerTaskListener() {
        runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getService").invoke(null) ?: error("ATM service is null")
            val listenerClass = Class.forName("android.app.ITaskStackListener")

            val binder =
                object : Binder() {
                    override fun onTransact(
                        code: Int,
                        data: Parcel,
                        reply: Parcel?,
                        flags: Int,
                    ): Boolean {
                        if (code == INTERFACE_TRANSACTION || code == PING_TRANSACTION || code == DUMP_TRANSACTION) {
                            return super.onTransact(code, data, reply, flags)
                        }
                        if (running) {
                            handler.removeCallbacks(debouncedRefresh)
                            handler.postDelayed(debouncedRefresh, DEBOUNCE_MS)
                        }
                        return true
                    }
                }

            val proxy =
                Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, _ ->
                    when (method.name) {
                        "asBinder" -> binder
                        "hashCode" -> binder.hashCode()
                        "equals" -> false
                        "toString" -> "SideveilTaskStackListenerProxy"
                        else -> null
                    }
                }

            atm.javaClass.getMethod("registerTaskStackListener", listenerClass).invoke(atm, proxy)
            atmService = atm
            registeredListener = proxy
            taskListenerActive = true
            SensorLog.info("Registered ATM ITaskStackListener")
        }.onFailure { err ->
            taskListenerActive = false
            SensorLog.warn("registerTaskStackListener unavailable; falling back to polling", err)
        }
    }

    private fun queryTopComponent(): Pair<String, String> {
        val fromTasks = queryViaAtmGetTasks()
        if (fromTasks.first.isNotBlank()) {
            return fromTasks
        }
        return queryViaOplusWindowManager()
    }

    private fun queryViaAtmGetTasks(): Pair<String, String> =
        runCatching {
            val atm =
                atmService
                    ?: Class
                        .forName("android.app.ActivityTaskManager")
                        .getMethod("getService")
                        .invoke(null)
                        ?.also { atmService = it }
                    ?: return "" to ""

            val method =
                getTasksMethod
                    ?: atm.javaClass
                        .getMethod(
                            "getTasks",
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        ).also { getTasksMethod = it }

            @Suppress("UNCHECKED_CAST")
            val tasks =
                (method.invoke(atm, 5, false, false, 0) as? List<ActivityManager.RunningTaskInfo>).orEmpty()

            for (task in tasks) {
                val component: ComponentName = task.topActivity ?: task.baseActivity ?: continue
                val pkg = component.packageName
                if (pkg.isNotBlank() && pkg != hostPackage) {
                    return@runCatching pkg to component.shortClassName.orEmpty()
                }
            }
            "" to ""
        }.onFailure { err ->
            SensorLog.warn("ATM.getTasks query failed", err)
        }.getOrDefault("" to "")

    private fun queryViaOplusWindowManager(): Pair<String, String> =
        runCatching {
            val rawFocus = oplusFocusCaller?.invoke()
            extractValidComponent(rawFocus, hostPackage)
        }.getOrDefault("" to "")

    companion object {
        private const val DEBOUNCE_MS = 50L
        private const val POLL_INTERVAL_MS = 2_000L
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        internal fun extractValidComponent(
            rawFocus: String?,
            hostPackage: String,
        ): Pair<String, String> {
            if (rawFocus.isNullOrBlank()) return "" to ""
            val trimmed = rawFocus.trim().substringBefore(' ').trim()
            val pkg = trimmed.substringBefore('/').trim()
            if (pkg.isEmpty() || pkg == hostPackage || !PACKAGE_NAME_REGEX.matches(pkg)) {
                return "" to ""
            }
            val act = if (trimmed.contains('/')) trimmed.substringAfter('/').trim() else ""
            return pkg to act
        }
    }
}
