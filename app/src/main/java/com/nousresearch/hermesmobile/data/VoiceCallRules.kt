package com.nousresearch.hermesmobile.data

import java.text.Normalizer

/**
 * Pure rules of a live voice call with a bot (Hermes "chained" voice loop: STT → the bot's normal turn → TTS).
 * Ports of Hermes's own rules, kept in step with them so the phone behaves like the CLI and the desktop:
 *  - [VoiceStop]: `apps/desktop/src/lib/voice-stop-word.ts` (whole utterance must be a stop phrase);
 *  - [TtsEcho]: `tools/voice_mode_transcript.is_tts_echo` / `voice-tts-echo.ts` (difflib ratio ≥ 0.6, sliding window);
 *  - [BargeDetector]: `tools/voice_mode._BargeDetector` (int16 RMS per 30 ms block, quiet-room floor held through
 *    playback, playback clamps, onset grace, 80 % windowed majority);
 *  - [SpeechSentences]: the per-sentence speaker used for providers without a PCM stream (min 20 chars).
 * No Android types here.
 */
object VoiceStop {
    /** The desktop's English list, used while `voice.stop_phrases` is left at its default. */
    val DEFAULT: List<String> = listOf(
        "stop", "stop listening", "stop it", "stop please", "please stop", "stop stop", "that is all", "that's all",
        "never mind", "nevermind", "end conversation", "end the conversation", "goodbye", "good bye", "bye", "cancel"
    )
    private val ADDRESS = listOf("hey hermes", "hermes", "ok", "okay", "hey")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        .replace(Regex("\\p{P}+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun stripAddress(text: String): String {
        for (prefix in ADDRESS) if (text != prefix && text.startsWith("$prefix ")) return text.removePrefix("$prefix ").trim()
        return text
    }

    /** True only when the WHOLE utterance is a stop command ("stop", "ok stop", "Hermes, goodbye."). */
    fun isStop(transcript: String, phrases: List<String> = DEFAULT): Boolean {
        val wanted = phrases.map(::normalize).filter { it.isNotEmpty() }
        val said = normalize(transcript)
        if (said.isEmpty() || wanted.isEmpty()) return false
        return said in wanted || stripAddress(said) in wanted
    }
}

object TtsEcho {
    const val THRESHOLD = 0.6
    const val MIN_FRAGMENT = 10

    private fun codePoints(text: String): List<Int> =
        text.replace(Regex("\\s+"), " ").trim().lowercase().codePoints().toArray().toList()

    /** `difflib.SequenceMatcher(None, a, b).ratio()` with autojunk, as CPython computes it. */
    fun ratio(a: List<Int>, b: List<Int>): Double {
        val total = a.size + b.size
        if (total == 0) return 1.0
        val b2j = HashMap<Int, MutableList<Int>>()
        b.forEachIndexed { j, ch -> b2j.getOrPut(ch) { mutableListOf() }.add(j) }
        if (b.size >= 200) {
            val ntest = b.size / 100 + 1
            b2j.entries.removeIf { it.value.size > ntest }
        }
        fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
            var besti = alo; var bestj = blo; var bestsize = 0
            var j2len = HashMap<Int, Int>()
            for (i in alo until ahi) {
                val next = HashMap<Int, Int>()
                for (j in b2j[a[i]].orEmpty()) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    next[j] = k
                    if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k }
                }
                j2len = next
            }
            while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) { besti--; bestj--; bestsize++ }
            while (besti + bestsize < ahi && bestj + bestsize < bhi && a[besti + bestsize] == b[bestj + bestsize]) bestsize++
            return Triple(besti, bestj, bestsize)
        }
        var matches = 0
        val queue = ArrayDeque<IntArray>().apply { add(intArrayOf(0, a.size, 0, b.size)) }
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast().let { listOf(it[0], it[1], it[2], it[3]) }
            val (i, j, k) = longest(alo, ahi, blo, bhi)
            if (k > 0) {
                matches += k
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return 2.0 * matches / total
    }

    /** True when [transcript] looks like the phone hearing [spoken] (what the bot was saying) through its own mic. */
    fun isEcho(transcript: String, spoken: String, threshold: Double = THRESHOLD): Boolean {
        val a = codePoints(transcript)
        val b = codePoints(spoken)
        if (a.isEmpty() || b.isEmpty()) return false
        if (ratio(a, b) >= threshold) return true
        if (a.size < MIN_FRAGMENT || a.size >= b.size) return false
        for (start in 0..(b.size - a.size)) if (ratio(a, b.subList(start, start + a.size)) >= threshold) return true
        return false
    }
}

/** Which part of the bot's turn the user talked over. */
enum class BargePhase { GENERATION, PLAYBACK }

/**
 * Per-block barge-in detector, a port of Hermes `tools/voice_mode._BargeDetector` (int16 RMS, 30 ms blocks).
 * The floor is calibrated from the quiet room (never from playback) and held while the bot speaks; the trigger is
 * floor × [multiplier], clamped up to [PLAYBACK_MIN_TRIGGER] while audio plays (so speaker bleed can't trip it) and
 * capped at [TRIGGER_CEILING] (so speech always can). Speech trips on ≥ 80 % of the last [tripBlocks] blocks above
 * the trigger; [graceBlocks] after playback starts (after a ≥ 1 s gap) are ignored.
 */
class BargeDetector(
    val multiplier: Double = DEFAULT_MULTIPLIER,
    private val calibBlocks: Int = 15,
    private val tripBlocks: Int = 10,
    private val graceBlocks: Int = 16
) {
    companion object {
        const val SILENCE_RMS = 200.0
        const val PLAYBACK_MIN_TRIGGER = 1500.0
        const val TRIGGER_CEILING = 4000.0
        const val DEFAULT_MULTIPLIER = 3.0
        const val BLOCK_MS = 30
    }

    private val tripNeeded = maxOf(1, Math.round(tripBlocks * 0.8).toInt())
    private val ambient = ArrayDeque<Double>()
    private val recentAbove = ArrayDeque<Boolean>()
    var quietFloor = SILENCE_RMS
        private set
    var floorLocked = false
        private set
    private var playingPrev = false
    private var playbackSeen = false
    private var graceRemaining = 0
    private var blocksSincePlayback = 10_000

    private fun pushAmbient(rms: Double) {
        ambient.addLast(rms)
        if (ambient.size > 100) ambient.removeFirst()
    }

    /** 90th percentile of the quiet window (numpy's linear interpolation), never below the silence threshold. */
    private fun floor(): Double {
        if (ambient.isEmpty()) return SILENCE_RMS
        val sorted = ambient.sorted()
        val pos = 0.9 * (sorted.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        val pct = sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        return maxOf(pct, SILENCE_RMS)
    }

    /** The trigger the next block is judged against (exposed for tests and diagnostics). */
    fun trigger(playing: Boolean): Double =
        minOf(maxOf(quietFloor * multiplier, if (playing) PLAYBACK_MIN_TRIGGER else SILENCE_RMS * 2), TRIGGER_CEILING)

    /** One 30 ms block's RMS; returns the phase when the user's speech trips, else null. */
    fun feed(rms: Double, playing: Boolean): BargePhase? {
        if (!floorLocked) {
            if (!playing) pushAmbient(rms)
            if (ambient.size >= calibBlocks || playing) { quietFloor = floor(); floorLocked = true } else return null
        }
        if (playing && !playingPrev) {
            if (!playbackSeen || blocksSincePlayback > 33) graceRemaining = graceBlocks
            playbackSeen = true
        }
        playingPrev = playing
        blocksSincePlayback = if (playing) 0 else blocksSincePlayback + 1
        val trigger = trigger(playing)
        if (!playing && rms < trigger) { pushAmbient(rms); quietFloor = floor() }
        var above = rms >= trigger
        if (above && graceRemaining > 0) above = false
        if (graceRemaining > 0) graceRemaining--
        recentAbove.addLast(above)
        if (recentAbove.size > tripBlocks) recentAbove.removeFirst()
        if (!(above && recentAbove.count { it } >= tripNeeded)) return null
        return if (playing) BargePhase.PLAYBACK else BargePhase.GENERATION
    }
}

/**
 * After speech trips: capture until [endpointMs] of quiet (below the silence threshold, or 1.5 × the room's floor in a
 * noisy room) or [maxMs] in all, like Hermes's `_capture_until_quiet`. [feed] returns true when the utterance ended.
 */
class UtteranceEndpointer(quietFloor: Double, endpointMs: Int = 1250, maxMs: Int = 30_000) {
    private val quietBelow = maxOf(BargeDetector.SILENCE_RMS, quietFloor * 1.5)
    private val endpointBlocks = maxOf(1, endpointMs / BargeDetector.BLOCK_MS)
    private val maxBlocks = maxOf(1, maxMs / BargeDetector.BLOCK_MS)
    private var quiet = 0
    private var blocks = 0
    fun feed(rms: Double): Boolean {
        blocks++
        quiet = if (rms < quietBelow) quiet + 1 else 0
        return quiet >= endpointBlocks || blocks >= maxBlocks
    }
}

/**
 * Cuts a streaming reply into speakable sentences (≥ [minLen] chars, ending on . ! ? … : or a line break) and
 * strips what should not be read aloud: code blocks, markdown marks, links (their text stays), bare URLs,
 * `MEDIA:` lines and think blocks.
 */
class SpeechSentences(private val minLen: Int = 20) {
    private val buf = StringBuilder()

    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        while (true) {
            val text = buf.toString()
            // The first sentence end that is not inside an open code block or think block (those are read as a whole).
            var cut: Int? = null
            var from = 0
            while (true) {
                val c = VoiceSpeech.sentenceEnd(text, minLen, from) ?: break
                val head = text.substring(0, c)
                if (!VoiceSpeech.insideFence(head) && !VoiceSpeech.insideThink(head)) { cut = c; break }
                from = c
            }
            if (cut == null) return out
            val sentence = VoiceSpeech.clean(text.substring(0, cut))
            buf.delete(0, cut)
            if (sentence.isNotBlank()) out += sentence
        }
    }

    /** The rest of the reply (turn ended). */
    fun flush(): List<String> {
        val rest = VoiceSpeech.clean(buf.toString())
        buf.setLength(0)
        return if (rest.isBlank()) emptyList() else listOf(rest)
    }

    fun reset() = buf.setLength(0)
}

object VoiceSpeech {
    fun insideFence(text: String): Boolean = Regex("```").findAll(text).count() % 2 == 1
    fun insideThink(text: String): Boolean = text.lastIndexOf("<think") > text.lastIndexOf("</think>")

    /** Index just past the first sentence end at or after [minLen] chars (searching from [from]), or null. */
    fun sentenceEnd(text: String, minLen: Int, from: Int = 0): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' && i >= 1 && text.substring(0, i).isNotBlank() && (i >= minLen || text.getOrNull(i + 1) == '\n')) return i + 1
            if (c in ".!?…:" && i + 1 < text.length && text[i + 1].isWhitespace() && i + 1 >= minLen) {
                // "e.g. ", "3.5" and "Dr. " stay inside the sentence.
                val word = text.substring(0, i).substringAfterLast(' ').lowercase()
                if (c != '.' || word !in setOf("e.g", "i.e", "dr", "mr", "mrs", "ms", "vs", "etc", "st")) return i + 1
            }
            i++
        }
        return null
    }

    fun clean(raw: String): String = raw
        .replace(Regex("(?s)<think>.*?</think>"), " ")
        .replace(Regex("(?s)```.*?```"), " ")
        .replace(Regex("(?m)^[ \\t]*MEDIA:\\S.*$"), " ")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("(?m)^[ \\t]*(#{1,6}|>|[-*+]|\\d+[.)])[ \\t]+"), "")
        .replace(Regex("[*_`~|]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** What the call says when the bot asks for an approval: never approvable by voice, only by tap. */
    fun approvalLine(botName: String): String = "$botName needs your OK, check the screen."
}

/** The bot chat a call belongs to. The call lives only while that very chat stays on screen on the same connection. */
data class VoiceCallBinding(
    val epoch: Long,
    val connection: String,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String
) {
    /**
     * True while [view] still shows this chat (compression may rotate one of the two ids, so either matching is the
     * same chat). Any navigation, push tap, other chat, Search/Bot details over it, or the connection ending → false.
     */
    fun stillOn(view: ChatView): Boolean =
        view.epoch == epoch && view.connection.orEmpty() == connection && view.chatPage && view.botChat && !view.readOnly &&
            view.profile == profile &&
            (view.storedSessionId == storedSessionId || view.runtimeSessionId == runtimeSessionId)
}

/** Where the call's audio goes. */
enum class CallRouteKind { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

/** What the call screen shows. */
data class VoiceCallUi(
    val profile: String,
    val label: String,
    val phase: VoiceCallLoop.Phase = VoiceCallLoop.Phase.LISTENING,
    val muted: Boolean = false,
    val route: CallRouteKind? = null,
    val routes: List<CallRouteKind> = emptyList(),
    /** A one-line hint ("Didn't catch that"), cleared when the user speaks again. */
    val notice: String? = null
)

object CallRoutes {
    /** First pick when the call starts or a headset (dis)connects: a Bluetooth headset, else a wired one, else the speaker. */
    fun preferred(available: List<CallRouteKind>): CallRouteKind? = listOf(
        CallRouteKind.BLUETOOTH, CallRouteKind.WIRED, CallRouteKind.SPEAKER, CallRouteKind.EARPIECE
    ).firstOrNull { it in available }

    /** Keep the user's pick while it exists; a newly connected headset takes over; a vanished route falls back. */
    fun next(current: CallRouteKind?, before: List<CallRouteKind>, now: List<CallRouteKind>): CallRouteKind? {
        val added = now - before.toSet()
        added.firstOrNull { it == CallRouteKind.BLUETOOTH || it == CallRouteKind.WIRED }?.let { return it }
        return if (current != null && current in now) current else preferred(now)
    }
}
