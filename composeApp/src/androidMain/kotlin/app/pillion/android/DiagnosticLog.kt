package app.pillion.android

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Best-effort on-device diagnostic log for real-bike bug reports where a computer/adb isn't
 * available. Mirrors the same lines already going to logcat into a small rotating file on the
 * phone, which Settings > Export diagnostic log shares out (WhatsApp, email, Drive, ...) via the
 * normal Android share sheet — no laptop needed at the roadside.
 */
object DiagnosticLog {
    private const val FILE_NAME = "pillion_diagnostic.log"

    /** Once the file grows past this, it's cleared and restarted rather than trimmed line-by-line. */
    private const val MAX_BYTES = 500_000L
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    /** Resolves and caches the log file's location. Safe to call repeatedly; cheap after the first call. */
    fun attach(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg, null)
    }

    fun w(tag: String, msg: String, error: Throwable? = null) {
        Log.w(tag, msg, error)
        write("W", tag, msg, error)
    }

    fun e(tag: String, msg: String, error: Throwable? = null) {
        Log.e(tag, msg, error)
        write("E", tag, msg, error)
    }

    /** The exportable log file. Attaches lazily so this works even if [attach] was never called yet. */
    fun logFile(context: Context): File {
        attach(context)
        return file ?: File(context.applicationContext.filesDir, FILE_NAME)
    }

    private fun write(level: String, tag: String, msg: String, error: Throwable?) {
        val f = file ?: return
        runCatching {
            synchronized(this) {
                if (f.exists() && f.length() > MAX_BYTES) f.delete()
                f.appendText("${timeFormat.format(Date())} $level/$tag: $msg\n")
                if (error != null) {
                    f.appendText("    ${error.javaClass.simpleName}: ${error.message}\n")
                    error.stackTrace.take(6).forEach { f.appendText("        at $it\n") }
                }
            }
        }
    }
}
