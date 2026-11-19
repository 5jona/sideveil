package pochita.sideveil.daemon

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.Os
import pochita.sideveil.shared.AppearancePatchParams
import pochita.sideveil.shared.SideveilConfig
import pochita.sideveil.shared.SideveilPrettyJson
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Loads, validates, watches, and atomically persists Sideveil configuration on disk.
 */
class ConfigStore(
    private val configFile: File,
    private val handler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull(),
    private val onChanged: (SideveilConfig) -> Unit,
) {
    private val lock = Any()

    @Volatile
    var current: SideveilConfig = SideveilConfig.DEFAULT
        private set

    private var observer: FileObserver? = null
    private val reloadRunnable = Runnable { reloadFromDisk(notifyIfUnchanged = false) }

    fun start() {
        val parent = configFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        if (!configFile.exists()) {
            writeAtomic(SideveilConfig.DEFAULT)
        }

        reloadFromDisk(notifyIfUnchanged = true)

        if (parent != null) {
            runCatching {
                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE
                val dirObserver =
                    object : FileObserver(parent, mask) {
                        override fun onEvent(
                            event: Int,
                            path: String?,
                        ) {
                            if (path == configFile.name || path == null) {
                                handler?.removeCallbacks(reloadRunnable)
                                handler?.postDelayed(reloadRunnable, 80L)
                            }
                        }
                    }
                dirObserver.startWatching()
                observer = dirObserver
                DaemonLog.info("Watching configuration directory: ${parent.absolutePath}")
            }
        }
    }

    fun reloadFromDisk(notifyIfUnchanged: Boolean = true): SideveilConfig {
        val loaded = readOrDefault()
        val changed: Boolean
        synchronized(lock) {
            changed = loaded != current
            current = loaded
        }
        if (changed || notifyIfUnchanged) {
            DaemonLog.info("Configuration loaded (enabled=${loaded.enabled}, side=${loaded.edge.side}, mode=${loaded.scope.mode})")
            onChanged(loaded)
        }
        return loaded
    }

    fun updateAppearance(patch: AppearancePatchParams): SideveilConfig {
        val updated =
            synchronized(lock) {
                val base = current
                val nextPanel =
                    base.panel
                        .copy(
                            themeId = patch.themeId ?: base.panel.themeId,
                            colorMode = patch.colorMode ?: base.panel.colorMode,
                            nativeBlurEnabled = patch.nativeBlurEnabled ?: base.panel.nativeBlurEnabled,
                            blurRadiusPx = patch.blurRadiusPx ?: base.panel.blurRadiusPx,
                            cornerRadiusDp = patch.cornerRadiusDp ?: base.panel.cornerRadiusDp,
                        ).sanitized()
                val next = base.copy(panel = nextPanel).sanitized()
                writeAtomic(next)
                current = next
                next
            }
        onChanged(updated)
        return updated
    }

    fun stop() {
        handler?.removeCallbacks(reloadRunnable)
        runCatching { observer?.stopWatching() }
        observer = null
    }

    private fun readOrDefault(): SideveilConfig =
        runCatching {
            val raw = configFile.readText(StandardCharsets.UTF_8)
            SideveilPrettyJson.decodeFromString<SideveilConfig>(raw).sanitized()
        }.getOrElse { err ->
            DaemonLog.warn("Failed to parse ${configFile.name}, keeping previous/default config", err)
            current
        }

    private fun writeAtomic(config: SideveilConfig) {
        val parent = configFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val pid = runCatching { android.os.Process.myPid() }.getOrDefault(0)
        val tmp = File(parent, ".${configFile.name}.tmp.$pid.${System.nanoTime()}")
        try {
            Files.deleteIfExists(tmp.toPath())
            tmp.createNewFile()
            applyMode0644(tmp)
            val json = SideveilPrettyJson.encodeToString(config.sanitized()) + "\n"
            tmp.writeText(json, StandardCharsets.UTF_8)
            applyMode0644(tmp)
            Files.move(
                tmp.toPath(),
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }

    private fun applyMode0644(file: File) {
        runCatching {
            Os.chmod(file.absolutePath, 420) // 0644 octal
        }.recoverCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }
    }
}
