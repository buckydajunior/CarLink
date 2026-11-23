package com.carlink.core.connection

import android.content.Context
import com.carlink.core.core.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AttemptOutcome(val atMillis: Long, val ok: Boolean, val millis: Long? = null,
    val kind: String? = null, val stage: ProgressStage? = null) {
    companion object { fun stallKind(stage: ProgressStage?) = "STALL_" + (stage?.name ?: "TOTAL") }
}
data class ProblemRecord(val atMillis: Long, val path: String, val stage: ProgressStage?, val kind: String, val detail: String?)
data class PathHealth(val path: String, val attempts: Int, val successes: Int, val medianTimeToPictureMillis: Long?, val lastOk: Boolean?)
data class PhoneHealth(val key: String, val engine: Engine, val phoneName: String?, val attempts: Int, val successes: Int,
    val successRate: Float?, val medianTimeToPictureMillis: Long?, val bestPath: String?, val paths: List<PathHealth>, val lastProblems: List<ProblemRecord>)

/** Bounded USB history preserves learned phase deadlines and useful failure evidence between drives. */
class ConnectionProfiles(private val storage: Storage = Storage.Memory(), private val wallClock: () -> Long = System::currentTimeMillis) {
    interface Storage {
        fun read(): String?
        fun write(text: String)
        class Memory(var text: String? = null) : Storage {
            override fun read() = text
            override fun write(text: String) { this.text = text }
        }
    }
    private class Profile(val key: String) {
        var phoneName: String? = null
        var lastUsed = 0L
        val outcomes = ArrayDeque<AttemptOutcome>()
        val samples = LinkedHashMap<String, ArrayDeque<Long>>()
        val problems = ArrayDeque<ProblemRecord>()
    }
    private val profiles = LinkedHashMap<String, Profile>()
    private val _health = MutableStateFlow<List<PhoneHealth>>(emptyList())
    val health: StateFlow<List<PhoneHealth>> = _health.asStateFlow()
    init { runCatching { storage.read()?.let(::decode) }; publish() }
    fun healthOf(request: SessionRequest): PhoneHealth? = profiles[keyOf(request)]?.let(::healthOf)
    fun recordStage(request: SessionRequest, stage: ProgressStage, millis: Long) {
        val p = profile(request)
        p.samples.getOrPut(normalize(stage).name) { ArrayDeque() }.push(millis, MAX_SAMPLES)
        save()
    }
    fun recordSuccess(request: SessionRequest, millis: Long) {
        val p = profile(request)
        p.outcomes.push(AttemptOutcome(wallClock(), true, millis), MAX_OUTCOMES)
        p.samples.getOrPut(TOTAL) { ArrayDeque() }.push(millis, MAX_SAMPLES)
        save()
    }
    fun recordFailure(request: SessionRequest, kind: String, stage: ProgressStage?, detail: String?) {
        val p = profile(request)
        p.outcomes.push(AttemptOutcome(wallClock(), false, kind = kind, stage = stage?.let(::normalize)), MAX_OUTCOMES)
        problem(p, stage, kind, detail)
        save()
    }
    fun recordProblem(request: SessionRequest, stage: ProgressStage?, kind: String, detail: String?) {
        problem(profile(request), stage, kind, detail); save()
    }
    fun deadline(request: SessionRequest, stage: ProgressStage?): Long =
        AdaptiveDeadlines.deadline(samples(request, stage), AdaptiveDeadlines.bounds(stage))
    fun usualMillis(request: SessionRequest, stage: ProgressStage?): Long? =
        samples(request, stage).takeIf { it.size >= AdaptiveDeadlines.MIN_SAMPLES }?.let(AdaptiveDeadlines::median)
    private fun samples(request: SessionRequest, stage: ProgressStage?): List<Long> =
        profiles[keyOf(request)]?.samples?.get(stage?.let(::normalize)?.name ?: TOTAL)?.toList().orEmpty()
    private fun profile(request: SessionRequest): Profile {
        val p = profiles.getOrPut(keyOf(request)) { Profile(keyOf(request)) }
        p.lastUsed = wallClock(); sanitize(request.phoneName)?.let { p.phoneName = it }
        if (profiles.size > MAX_PROFILES) profiles.values.minByOrNull { it.lastUsed }?.takeIf { it !== p }?.let { profiles.remove(it.key) }
        return p
    }
    private fun problem(p: Profile, stage: ProgressStage?, kind: String, detail: String?) {
        p.problems.push(ProblemRecord(wallClock(), USB, stage?.let(::normalize), kind, sanitize(detail)), MAX_PROBLEMS)
    }
    private fun save() { runCatching { storage.write(encode()) }; publish() }
    private fun publish() { _health.value = profiles.values.sortedByDescending { it.lastUsed }.map(::healthOf) }
    private fun healthOf(p: Profile): PhoneHealth {
        val successes = p.outcomes.count { it.ok }
        val median = AdaptiveDeadlines.median(p.outcomes.filter { it.ok }.mapNotNull { it.millis })
        return PhoneHealth(p.key, Engine.CARPLAY, p.phoneName, p.outcomes.size, successes,
            if (p.outcomes.isEmpty()) null else successes.toFloat() / p.outcomes.size, median,
            if (successes > 0) USB else null, listOf(PathHealth(USB, p.outcomes.size, successes, median, p.outcomes.lastOrNull()?.ok)),
            p.problems.reversed().take(HEALTH_PROBLEMS))
    }
    // Keep the existing format so a prior F-150 build's USB history survives this source pruning.
    internal fun encode(): String = buildString {
        appendLine(HEADER)
        profiles.values.forEach { p ->
            appendLine(listOf("P", p.key, Engine.CARPLAY.name, clean(p.phoneName), p.lastUsed).joinToString(TAB))
            p.outcomes.forEach { o -> appendLine(listOf("O", USB, o.atMillis, if (o.ok) "1" else "0",
                o.millis ?: NONE, o.kind ?: NONE, o.stage?.name ?: NONE).joinToString(TAB)) }
            p.samples.forEach { (key, values) -> appendLine(listOf("S", "$USB/$key", values.joinToString(",")).joinToString(TAB)) }
            p.problems.forEach { r -> appendLine(listOf("R", r.atMillis, USB, r.stage?.name ?: NONE, r.kind, clean(r.detail)).joinToString(TAB)) }
        }
    }
    private fun decode(text: String) {
        val lines = text.lines(); if (lines.firstOrNull() != HEADER) return
        var current: Profile? = null
        for (line in lines.drop(1)) {
            val f = line.split(TAB)
            runCatching {
                when (f[0]) {
                    "P" -> {
                        current = null
                        if (f[2] == Engine.CARPLAY.name && f[1].startsWith("CARPLAY:usb:")) {
                            current = Profile(f[1]).also { it.phoneName = f[3].takeUnless { n -> n == NONE }; it.lastUsed = f[4].toLong(); profiles[it.key] = it }
                        }
                    }
                    "O" -> if (f[1] == USB) current?.outcomes?.push(AttemptOutcome(f[2].toLong(), f[3] == "1",
                        f[4].takeUnless { it == NONE }?.toLong(), f[5].takeUnless { it == NONE }, stageOf(f[6])), MAX_OUTCOMES)
                    "S" -> if (f[1].startsWith("USB/")) f[2].split(',').filter { it.isNotEmpty() }.forEach { value ->
                        current?.samples?.getOrPut(f[1].substringAfter('/')) { ArrayDeque() }?.push(value.toLong(), MAX_SAMPLES)
                    }
                    "R" -> if (f[2] == USB) current?.problems?.push(ProblemRecord(f[1].toLong(), USB, stageOf(f[3]), f[4], f[5].takeUnless { it == NONE }), MAX_PROBLEMS)
                }
            }
        }
        while (profiles.size > MAX_PROFILES) profiles.remove(profiles.values.minByOrNull { it.lastUsed }!!.key)
    }
    private fun stageOf(name: String): ProgressStage? = runCatching { ProgressStage.valueOf(name) }.getOrNull()
    private fun clean(text: String?) = sanitize(text) ?: NONE
    private fun sanitize(text: String?): String? = text?.replace(Regex("[\t\r\n]+"), " ")?.trim()?.take(200)?.takeUnless { it.isEmpty() || it == NONE }
    private fun <T> ArrayDeque<T>.push(value: T, max: Int) { addLast(value); while (size > max) removeFirst() }
    companion object {
        const val MAX_OUTCOMES = 20
        const val MAX_SAMPLES = 15
        const val MAX_PROBLEMS = 12
        const val MAX_PROFILES = 16
        const val HEALTH_PROBLEMS = 5
        const val USB = "USB"
        private const val HEADER = "carlink-profiles 1"
        private const val TAB = "\t"
        private const val NONE = "-"
        private const val TOTAL = "TOTAL"
        fun normalize(stage: ProgressStage) = if (stage == ProgressStage.PHONE_NOT_ANSWERING) ProgressStage.WAITING_FOR_PHONE else stage
        fun keyOf(request: SessionRequest): String = "CARPLAY:usb:" +
            (request.phoneName?.replace(Regex("\\s+"), " ")?.trim()?.lowercase()?.ifBlank { null } ?: "phone")
        fun sharedPreferences(context: Context): ConnectionProfiles {
            val prefs = context.applicationContext.getSharedPreferences("carlink_connection_profiles", Context.MODE_PRIVATE)
            return ConnectionProfiles(object : Storage {
                override fun read() = prefs.getString("profiles", null)
                override fun write(text: String) { prefs.edit().putString("profiles", text).apply() }
            })
        }
    }
}
