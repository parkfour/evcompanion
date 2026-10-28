package com.evcompanion.core.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in replacement for [android.util.Log]'s w/i/e - same call shape, so it's a direct swap in
 * the OBD polling/transport layer - that also appends every line to a plain-text file under the
 * app's external files dir. Logcat's ring buffer rotates in minutes; a drive far from a computer
 * otherwise leaves nothing to debug by the time anyone's back near adb. Share the file via the
 * phone UI's "Share debug log" action (see BluetoothPickerScreen) once back in range of anything
 * that can send it - no USB/adb needed.
 *
 * Opens, appends, and closes the file on every single call rather than holding a long-lived
 * writer open - slower, but guarantees nothing written is ever lost to an app kill or a dead
 * battery mid-drive, which matters far more than throughput for a debug log.
 */
object DebugFileLogger {
    private var file: File? = null
    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "DebugFileLoggerThread").apply { isDaemon = true }
    }

    private const val PREFS = "debug_prefs"
    private const val ENABLED_KEY = "debug_features_enabled"

    private val _enabled = MutableStateFlow(false)
    /**
     * The "Enable debugging features" toggle. Off by default for a release build: logcat still
     * gets every line, but nothing is written to the capture file and the phone UI hides the
     * share/replay actions. Persisted so a tester only flips it once.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, value).apply()
        _enabled.value = value
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, false)
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.mkdirs()
            trimOldLogs(dir)
            val name = "obd_debug_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            file = File(dir, name)
        }
    }

    /** The log file for this app run, once [init] has been called - null before then. */
    fun currentLogFile(): File? = file

    /** Every captured debug log still on disk (newest first) - see [trimOldLogs] for retention. */
    fun listLogFiles(context: Context): List<File> {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return dir.listFiles { f -> f.name.startsWith("obd_debug_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Copies an already-captured log from outside the app (e.g. one previously shared off a
     * different device, or pulled onto a computer and picked via the system file picker) into
     * the same directory and retention [listLogFiles] scans, so it can be replayed here too -
     * not just logs still sitting in this app's own storage from this device.
     */
    fun importLogFile(context: Context, lines: List<String>): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val name = "obd_debug_imported_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val target = File(dir, name)
        target.writeText(lines.joinToString("\n"))
        trimOldLogs(dir)
        return target
    }

    fun w(tag: String, message: String) = write("W", tag, message)
    fun i(tag: String, message: String) = write("I", tag, message)
    fun e(tag: String, message: String) = write("E", tag, message)

    private fun write(level: String, tag: String, message: String) {
        runCatching {
            when (level) {
                "W" -> Log.w(tag, message)
                "I" -> Log.i(tag, message)
                else -> Log.e(tag, message)
            }
        }
        if (!_enabled.value) return
        val target = file ?: return
        val timestamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
        executor.execute {
            try {
                FileWriter(target, true).use { it.write("$timestamp $level/$tag: $message\n") }
            } catch (e: Exception) {
                // Logging must never crash the app it's trying to help debug.
            }
        }
    }

    /** Keeps at most the last few runs' logs so a long debugging session doesn't fill up storage unbounded. */
    private fun trimOldLogs(dir: File, keep: Int = 5) {
        dir.listFiles { f -> f.name.startsWith("obd_debug_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }
}
