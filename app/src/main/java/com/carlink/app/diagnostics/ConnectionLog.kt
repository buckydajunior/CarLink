package com.carlink.app.diagnostics

import android.content.Context
import com.carlink.core.diagnostics.EventLog
import com.carlink.core.diagnostics.LogMasking
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Where a connection-log line came from. */
enum class LogSource { CARPLAY, CARLINK }

/** One line of the combined connection log. [level] is V/D/I/W/E/A when the source records it. */
data class LogLine(val timeMillis: Long, val source: LogSource, val level: Char?, val text: String) {
}

/** Merges bounded wired CarPlay logs with this app's saved timing events for export. */
object ConnectionLog {
    /** Lines kept per source; the screen shows the newest first. */
    const val LINES_PER_SOURCE = 400
    private const val TAIL_BYTES = 192 * 1024L

    fun diPlayFiles(context: Context): List<File> =
        File(context.filesDir, "logs").let { listOf(File(it, "previous.log"), File(it, "diplay.log")) }

    /** Wired CarPlay's recent lines, oldest first. */
    fun readEngines(context: Context): List<List<LogLine>> = listOf(
        diPlayFiles(context).flatMap { file -> parseDiPlay(tail(file), file.lastModified()) }.takeLast(LINES_PER_SOURCE),
    )

    /** CarLink's own events as log lines, oldest first. */
    fun events(entries: List<EventLog.Entry>): List<LogLine> =
        entries.takeLast(LINES_PER_SOURCE).map { LogLine(it.timeMillis, LogSource.CARLINK, null, "${it.tag}: ${it.message}") }

    /** Every source merged, newest first. */
    fun read(context: Context, entries: List<EventLog.Entry> = EventLog.entries.value): List<LogLine> =
        merge(*(readEngines(context) + listOf(events(withSaved(context, entries)))).toTypedArray())

    /**
     * The in-memory events start at this process's start; a report filed after a restart (the
     * ignition was off) needs the earlier ones from `files/logs/carlink*.log` too.
     */
    fun withSaved(context: Context, entries: List<EventLog.Entry>): List<EventLog.Entry> {
        val firstInMemory = entries.firstOrNull()?.timeMillis ?: Long.MAX_VALUE
        val dir = File(context.filesDir, "logs")
        val saved = listOf(File(dir, "carlink.previous.log"), File(dir, "carlink.log"))
            .filter { it.isFile }.flatMap { tail(it) }.mapNotNull(::parseSaved).filter { it.timeMillis < firstInMemory }
        return saved + entries
    }

    /** One `carlink.log` line, "epochMillis [tag] message" (EventLog.add), or null. */
    fun parseSaved(line: String): EventLog.Entry? {
        val match = SAVED_LINE.matchEntire(line.trimEnd()) ?: return null
        return EventLog.Entry(match.groupValues[1].toLongOrNull() ?: return null, match.groupValues[2], match.groupValues[3])
    }

    private val SAVED_LINE = Regex("""^(\d{10,})\s+\[([^\]]*)]\s?(.*)$""")

    /** Merges sources given oldest first into one list, newest first; same-millisecond lines keep their order reversed. */
    fun merge(vararg sources: List<LogLine>): List<LogLine> =
        sources.toList().flatMap { it.asReversed() }.sortedByDescending { it.timeMillis }

    /**
     * DiPlay: a header "DiPlay log started yyyy-MM-dd HH:mm:ss.SSS …", then "HH:mm:ss.SSS  <message>".
     * Lines carry only the time of day, so the date comes from the header (or the file's
     * modification time) and moves on a day when the clock wraps past midnight.
     */
    fun parseDiPlay(lines: List<String>, fileModified: Long): List<LogLine> {
        val header = lines.firstNotNullOfOrNull { DIPLAY_HEADER.find(it) }
        val day = Calendar.getInstance()
        val start = header?.let { runCatching { headerFormat().parse(it.groupValues[1]) }.getOrNull() }
        day.time = start ?: Date(if (fileModified > 0) fileModified else System.currentTimeMillis())
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0)
        var lastTime = -1L
        val out = ArrayList<LogLine>(lines.size)
        for (raw in lines) {
            val match = DIPLAY_LINE.matchEntire(raw.trimEnd()) ?: continue
            val (h, m, s, ms) = match.destructured
            var time = day.timeInMillis + ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
            if (lastTime >= 0 && time < lastTime - 60 * 60 * 1000) {
                day.add(Calendar.DAY_OF_MONTH, 1)
                time += 24 * 60 * 60 * 1000L
            }
            lastTime = time
            val message = match.groupValues[5]
            out += LogLine(time, LogSource.CARPLAY, diPlayLevel(message), LogMasking.mask(message))
        }
        return out
    }

    private fun diPlayLevel(message: String): Char? {
        val lower = message.lowercase(Locale.ROOT)
        return when {
            // DiPlay's 10 s wired io summary: an idle iAP2 control read times out every 100 ms in a
            // healthy session (issue #5 reports were all such lines). Only a failure or end is a problem.
            "wired io " in lower -> when {
                "result=failed" in lower -> 'E'
                "result=ended" in lower -> 'W'
                else -> null
            }
            "error" in lower || "failed" in lower || "exception" in lower -> 'E'
            "warn" in lower || "timeout" in lower || "timed out" in lower -> 'W'
            else -> null
        }
    }

    /** The last [TAIL_BYTES] of a file as lines (the first, likely partial, line dropped). */
    internal fun tail(file: File): List<String> = runCatching {
        if (!file.isFile) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val lines = String(bytes, Charsets.UTF_8).lines()
            // Keep DiPlay's header even when the tail starts mid-file: it carries the date.
            if (start > 0) lines.drop(1) else lines
        }
    }.getOrDefault(emptyList())

    /** One plain-text report of every line, oldest first, for sharing. */
    fun format(lines: List<LogLine>, header: String): String = buildString {
        appendLine(header)
        appendLine()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        for (line in lines.sortedBy { it.timeMillis }) {
            append(time.format(Date(line.timeMillis))).append("  ")
            append(sourceTag(line.source)).append(' ')
            append(line.level ?: '-').append("  ")
            appendLine(line.text)
        }
    }

    fun sourceTag(source: LogSource): String = when (source) {
        LogSource.CARPLAY -> "CP"
        LogSource.CARLINK -> "CL"
    }

    /** Writes a bounded USB report to the private share cache, with no radio or device probes. */
    fun export(context: Context, header: String): File {
        val dir = File(context.cacheDir, CarLinkFileProvider.SHARED_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "carlink-connection-log-$stamp.txt").apply {
            writeText(format(read(context), "$header\nConnection: USB CarPlay"))
        }
    }

    private fun headerFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val DIPLAY_LINE = Regex("(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(.*)")
    private val DIPLAY_HEADER = Regex("DiPlay log started (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
}
