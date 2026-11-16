package com.nousresearch.hermesmobile.data

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's microphone as the call uses it. [CallMic] creates one per listening window; Android's AudioRecord in
 * VoiceCallEngine, a scripted fake in tests.
 */
interface MicRecorder {
    /** Start capturing. A new recorder only ever delivers audio recorded after this call. */
    fun start()
    /** Blocking read of up to [buf].size samples; <= 0 once stopped or on error. */
    fun read(buf: ShortArray): Int
    /** Stop capturing now. Called from the main thread while the reader may be blocked in [read]; unblocks it. */
    fun stop()
    /** Free the recorder and everything it buffered. Only from the reader thread, after its last [read]. */
    fun release()
}

/**
 * The call's microphone: open only while the call is listening.
 *
 * Privacy rule (Tzvi, 2026-11-15): while muted, on hold (audio focus lost), transcribing or ended, the microphone is
 * off. No recorder exists, so nothing is recorded and nothing recorded earlier survives.
 *  - [arm] opens a NEW recorder (on its own reader thread) with a fresh [MicCapture]: empty pre-roll, new barge
 *    detector, no capture.
 *  - [pause] stops that recorder at once (from the caller's thread, which unblocks the reader); the reader then
 *    releases it and drops every buffer: the recorder's own, the pre-roll and any capture. A block the reader got
 *    back after the pause is discarded, never fed. There is no time-based filtering; the recorder simply isn't there.
 *  - A new recorder is opened only after the previous one is released, so two never overlap.
 *  - Every event carries the token of the [arm] it came from; the caller drops one whose window has since closed.
 *
 * [arm] / [pause] are called from one thread (the main thread in the app).
 */
class CallMic(
    private val open: (token: Long) -> MicRecorder,
    private val blockSamples: Int,
    private val playing: () -> Boolean,
    private val emit: (token: Long, event: MicCapture.Event) -> Unit,
    private val failed: (token: Long, error: Throwable) -> Unit,
    private val rate: Int = 16_000,
    private val spawn: (Runnable) -> Unit = { Thread(it, "hermes-call-mic").start() }
) {
    private var current: Session? = null
    private var last: Session? = null

    /** Open a new recorder for the listening window [token] (closing any open one first). */
    fun arm(token: Long) {
        pause()
        val s = Session(token, last)
        current = s
        last = s
        spawn(s::run)
    }

    /** Microphone off: stop the recorder now; its reader releases it and drops every buffer. */
    fun pause() {
        current?.cancel()
        current = null
    }

    /** Wait until every recorder opened so far is released (tests). */
    fun awaitReleased(timeoutMs: Long): Boolean = last?.released?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true

    private inner class Session(val token: Long, private val previous: Session?) {
        private val lock = Any()
        private var cancelled = false // under lock
        private var rec: MicRecorder? = null // under lock; set while it may be recording
        val released = CountDownLatch(1)

        fun cancel() = synchronized(lock) {
            cancelled = true
            rec?.let { runCatching { it.stop() } }
        }

        private fun live() = synchronized(lock) { !cancelled }

        fun run() {
            try {
                // Never two recorders at once: the previous window's reader is already stopping.
                previous?.released?.await(2, TimeUnit.SECONDS)
                if (!live()) return
                val r = try { open(token) } catch (e: Throwable) { if (live()) failed(token, e); return }
                val capture = MicCapture(rate)
                try {
                    synchronized(lock) {
                        if (cancelled) return
                        rec = r
                        r.start()
                    }
                    val buf = ShortArray(blockSamples)
                    while (true) {
                        val n = r.read(buf)
                        if (!live()) break // read after the pause: dropped with everything else
                        if (n <= 0) { Thread.sleep(10); continue }
                        capture.feed(buf.copyOf(n), playing())?.let { emit(token, it) }
                    }
                } catch (e: Throwable) {
                    if (live()) failed(token, e)
                } finally {
                    synchronized(lock) { rec = null }
                    capture.reset()
                    runCatching { r.stop() }
                    runCatching { r.release() }
                }
            } finally {
                released.countDown()
            }
        }
    }
}

/**
 * One listening window's audio, kept pure so it can be tested with real frames and WAVs: the 1.2 s pre-roll, the
 * barge detector, the capture and the endpointer. A fresh one per recorder ([CallMic.arm]), fed only by that
 * recorder's reader thread; it ends after one utterance.
 */
class MicCapture(private val rate: Int = 16_000, private val preRollBlocks: Int = 40) {
    sealed interface Event {
        /** The user started talking ([phase] tells over what). */
        data class SpeechStart(val phase: BargePhase) : Event
        /** The utterance ended: a 16 kHz mono 16-bit WAV, pre-roll included. */
        class Utterance(val wav: ByteArray) : Event
    }

    private enum class Mode { LISTENING, CAPTURING, DONE }

    private var mode = Mode.LISTENING
    private val preRoll = ArrayDeque<ShortArray>()
    private val detector = BargeDetector()
    private var capture: MutableList<ShortArray>? = null
    private var endpointer: UtteranceEndpointer? = null

    /** One block from the recorder. */
    fun feed(frame: ShortArray, playing: Boolean): Event? {
        val rms = rms(frame)
        return when (mode) {
            Mode.LISTENING -> {
                preRoll.addLast(frame)
                if (preRoll.size > preRollBlocks) preRoll.removeFirst()
                val phase = detector.feed(rms, playing) ?: return null
                mode = Mode.CAPTURING
                capture = preRoll.toMutableList()
                preRoll.clear()
                endpointer = UtteranceEndpointer(detector.quietFloor)
                Event.SpeechStart(phase)
            }
            Mode.CAPTURING -> {
                val c = capture ?: return null
                c.add(frame)
                if (endpointer?.feed(rms) != true) return null
                val out = wav(c)
                reset()
                Event.Utterance(out)
            }
            Mode.DONE -> null
        }
    }

    /** Drop everything; nothing more is captured. */
    fun reset() {
        mode = Mode.DONE
        preRoll.clear()
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
