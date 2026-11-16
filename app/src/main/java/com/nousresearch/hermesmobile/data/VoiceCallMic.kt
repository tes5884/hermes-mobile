package com.nousresearch.hermesmobile.data

import java.io.ByteArrayOutputStream

/**
 * What the call's mic thread does with each 30 ms block, kept pure so it can be tested with real frames and WAVs.
 *
 * Privacy rule: audio recorded while the mic is not armed (muted, on hold, transcribing) never leaves the phone.
 *  - [pause] and every new [arm] start a fresh window: the pre-roll, the barge detector and any capture are dropped
 *    the first time the mic thread sees the change, so no buffer ever spans a paused interval.
 *  - A block whose recording started before the current [arm] (still sitting in the recorder's buffer when the user
 *    unmuted or the call came off hold) is discarded, not added to the pre-roll.
 *  - Mode changes are made under one lock. The mic thread's own moves (armed → capturing on a trip, capturing →
 *    paused at the end of an utterance) only happen if the main thread hasn't paused or re-armed in between, so a
 *    mute can never be overwritten by the mic thread.
 *
 * [arm] / [pause] are called from the main thread; [feed] only from the mic thread.
 */
class MicCapture(private val rate: Int = 16_000, private val preRollBlocks: Int = 40) {
    sealed interface Event {
        val token: Long
        /** The user started talking ([phase] tells over what). */
        data class SpeechStart(override val token: Long, val phase: BargePhase) : Event
        /** The utterance ended: a 16 kHz mono 16-bit WAV, pre-roll included. */
        class Utterance(override val token: Long, val wav: ByteArray) : Event
    }

    private enum class Mode { PAUSED, ARMED, CAPTURING }

    private val lock = Any()
    // Shared: written only under [lock].
    private var mode = Mode.PAUSED
    private var token = 0L
    private var openedAt = Long.MAX_VALUE

    // Mic thread only.
    private val preRoll = ArrayDeque<ShortArray>()
    private var detector: BargeDetector? = null
    private var capture: MutableList<ShortArray>? = null
    private var endpointer: UtteranceEndpointer? = null
    private var seenToken = Long.MIN_VALUE

    /** Listen under [t]; only audio whose recording starts at or after [atNanos] (monotonic clock) is used. */
    fun arm(t: Long, atNanos: Long) = synchronized(lock) { token = t; mode = Mode.ARMED; openedAt = atNanos }

    /** Stop listening (mute, hold, transcribing, end); whatever was buffered is dropped. */
    fun pause(t: Long) = synchronized(lock) { token = t; mode = Mode.PAUSED; openedAt = Long.MAX_VALUE }

    /** One block from the recorder; [startedAtNanos] = when its first sample was recorded (never later than true). */
    fun feed(frame: ShortArray, startedAtNanos: Long, playing: Boolean): Event? {
        val m: Mode; val t: Long; val opened: Long
        synchronized(lock) { m = mode; t = token; opened = openedAt }
        if (t != seenToken) { reset(); seenToken = t }
        if (m == Mode.PAUSED) { reset(); return null }
        if (startedAtNanos < opened) return null
        preRoll.addLast(frame)
        if (preRoll.size > preRollBlocks) preRoll.removeFirst()
        val rms = rms(frame)
        return when (m) {
            Mode.ARMED -> {
                val d = detector ?: BargeDetector().also { detector = it }
                val phase = d.feed(rms, playing) ?: return null
                if (!move(t, Mode.ARMED, Mode.CAPTURING)) { reset(); return null }
                capture = preRoll.toMutableList()
                endpointer = UtteranceEndpointer(d.quietFloor)
                Event.SpeechStart(t, phase)
            }
            Mode.CAPTURING -> {
                val c = capture ?: run { reset(); return null }
                c.add(frame)
                if (endpointer?.feed(rms) != true) return null
                if (!move(t, Mode.CAPTURING, Mode.PAUSED)) { reset(); return null }
                val out = wav(c)
                reset()
                Event.Utterance(t, out)
            }
            Mode.PAUSED -> null
        }
    }

    /** The mic thread's own transition, only if the main thread hasn't paused or re-armed since [t] was read. */
    private fun move(t: Long, from: Mode, to: Mode): Boolean = synchronized(lock) {
        if (token != t || mode != from) false else { mode = to; true }
    }

    private fun reset() {
        preRoll.clear()
        detector = null
        capture = null
        endpointer = null
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return kotlin.math.sqrt(sum / frame.size.coerceAtLeast(1))
    }

    /** 16 kHz mono 16-bit WAV, what Hermes's STT path expects. */
    fun wav(frames: List<ShortArray>): ByteArray {
        val samples = frames.sumOf { it.size }
        val out = ByteArrayOutputStream(44 + samples * 2)
        fun int(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + samples * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(rate); int(rate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(samples * 2)
        for (f in frames) for (s in f) short(s.toInt())
        return out.toByteArray()
    }
}
