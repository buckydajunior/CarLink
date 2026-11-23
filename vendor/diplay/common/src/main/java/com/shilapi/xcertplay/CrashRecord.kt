package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile // CarLink: bound host crash-report reads.
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Keeps DiPlay's last uncaught exception so the next diagnostic report shows where it crashed. Android 11+ only
 * records `reason=java_crash`, which left a crash inside the DiYou launcher card without a cause (#463). The record
 * stays on the head unit, and the report redacts it like any log line.
 */
internal object CrashRecord {
    private const val FILE = "logs/last-crash.txt"
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 12
    private const val MAX_OWN_FRAMES = 6
    private const val MAX_MESSAGE = 120
    private const val MAX_REPORT_BYTES = 64 * 1024 // CarLink: corrupt/older records cannot grow the export without bound.
    private const val MAX_REPORT_LINES = 96 // CarLink: includes the bounded cause/frame chain.
    private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        val file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Recording must never replace the crash itself.
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(format(thread.name, error, System.currentTimeMillis()))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun format(threadName: String, error: Throwable, atMillis: Long): String = buildString {
        appendLine("crash atEpochMs=$atMillis thread=${threadName.take(40)}")
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.size < MAX_CAUSES && seen.add(current)) {
            val message = current.message?.lineSequence()?.firstOrNull()?.take(MAX_MESSAGE)
            appendLine((if (seen.size == 1) "" else "caused by ") + current.javaClass.name + (message?.let { ": $it" } ?: ""))
            val frames = current.stackTrace
            frames.take(MAX_FRAMES).forEach { appendLine("  at $it") }
            // A crash inside a framework call fills the first frames with Android's own; the DiYou card's
            // startActivity crash (#463) lost the DiPlay code that called it. Keep the first few of ours.
            val rest = frames.drop(MAX_FRAMES)
            val own = rest.filter(::ownFrame).take(MAX_OWN_FRAMES)
            if (own.isNotEmpty()) {
                appendLine("  ... App/engine frames further down:") // CarLink: host frames are retained too.
                own.forEach { appendLine("  at $it") }
            }
            if (rest.size > own.size) appendLine("  ... ${rest.size - own.size} more")
            current = current.cause
        }
    }

    private fun ownFrame(frame: StackTraceElement): Boolean =
        frame.className.startsWith("com.shilapi.") || frame.className.startsWith("com.shihab.") ||
            frame.className.startsWith("com.carlink.") // CarLink: framework errors must retain the host startup caller.

    fun report(context: Context): String {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return "No crash recorded by this version."
        return runCatching {
            // CarLink: preserve the existing redactor while bounding bytes/lines before export.
            RandomAccessFile(file, "r").use { input ->
                val size = input.length()
                val start = (size - MAX_REPORT_BYTES).coerceAtLeast(0)
                input.seek(start)
                val bytes = ByteArray((size - start).toInt())
                input.readFully(bytes)
                val lines = String(bytes, Charsets.UTF_8).lines().let { if (start > 0) it.drop(1) else it }
                val kept = lines.takeLast(MAX_REPORT_LINES)
                buildString {
                    if (start > 0 || lines.size > MAX_REPORT_LINES) appendLine("Older crash record content omitted.")
                    append(kept.mapNotNull(DiagnosticRedactor::redact).joinToString("\n"))
                }
            }
        }
            .getOrElse { "Crash record unreadable: ${it.javaClass.simpleName}" }
    }
}

/** Installs [CrashRecord] when the process starts, before any activity, receiver or service runs. */
class CrashRecordInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(CrashRecord::install)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
