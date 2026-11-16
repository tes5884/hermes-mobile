package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BargeDetector
import com.nousresearch.hermesmobile.data.MicCapture
import com.nousresearch.hermesmobile.data.VoiceCallLoop
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The call's mic buffer (Shomer t_215dfd5f): audio recorded while muted or on hold never reaches a later
 * utterance's WAV. Real 30 ms frames go through the real loop + MicCapture the way VoiceCallEngine wires them, and
 * the WAV that would be uploaded is decoded and checked sample by sample.
 */
class VoiceCallMicTest {
    private val blockSamples = 16_000 * BargeDetector.BLOCK_MS / 1000
    private val blockNanos = BargeDetector.BLOCK_MS * 1_000_000L

    // Each phase of a test gets its own amplitude, so a decoded WAV shows where every sample came from.
    private val PRE_QUIET = 41; private val PRE_SPEECH = 4111
    private val MUTED_SPEECH = 5111; private val MUTED_QUIET = 51
    private val POST_QUIET = 32; private val POST_SPEECH = 3222

    private fun frame(amp: Int) = ShortArray(blockSamples) { i -> (if (i % 2 == 0) amp else -amp).toShort() }

    /** VoiceCallEngine's wiring of loop ↔ mic, on a fake clock where every fed frame takes 30 ms. */
    private inner class Rig : VoiceCallLoop.Effects {
        val mic = MicCapture()
        var clock = 1_000_000_000L
        var armToken = 0L
        val uploads = mutableListOf<ByteArray>()
        val loop = VoiceCallLoop(this) { clock / 1_000_000 }

        override fun armMic() { armToken++; mic.arm(armToken, clock) }
        override fun pauseMic() { armToken++; mic.pause(armToken) }
        override fun stopSpeech() {}
        override fun speak(sentence: String) {}
        override fun interruptTurn() {}
        override fun transcribe(wav: ByteArray, token: Long) { uploads += wav }
        override fun submit(text: String, interrupted: Boolean) {}
        override fun phaseChanged(phase: VoiceCallLoop.Phase) {}
        override fun notice(notice: VoiceCallLoop.Notice) {}
        override fun ended(reason: VoiceCallLoop.EndReason) {}

        /** [count] frames recorded now (or [recordedAt], for a block still in the recorder's buffer) and read. */
        fun feed(amp: Int, count: Int, recordedAt: (Int) -> Long = { clock }) {
            repeat(count) { i ->
                when (val e = mic.feed(frame(amp), recordedAt(i), playing = false)) {
                    is MicCapture.Event.SpeechStart -> if (e.token == armToken) loop.onSpeechStart(false)
                    is MicCapture.Event.Utterance -> if (e.token == armToken) loop.onUtterance(e.wav)
                    null -> Unit
                }
                clock += blockNanos
            }
        }

        /** The user says something: room calibration, speech, then the quiet that ends the utterance. */
        fun utterance(quiet: Int, speech: Int) { feed(quiet, 20); feed(speech, 15); feed(quiet, 45) }
    }

    /** Distinct |sample| values in a 16-bit mono WAV, after checking its header. */
    private fun amplitudes(wav: ByteArray): Set<Int> {
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals("data", String(wav, 36, 4))
        val bytes = (wav[40].toInt() and 0xff) or ((wav[41].toInt() and 0xff) shl 8) or
            ((wav[42].toInt() and 0xff) shl 16) or ((wav[43].toInt() and 0xff) shl 24)
        assertEquals(wav.size - 44, bytes)
        return (0 until bytes / 2).map { i ->
            kotlin.math.abs(((wav[44 + 2 * i].toInt() and 0xff) or (wav[45 + 2 * i].toInt() shl 8)).toShort().toInt())
        }.toSet()
    }

    @Test fun speechWhileMutedIsNotInTheFirstUtteranceAfterUnmute() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.feed(PRE_QUIET, 20)
        rig.loop.setMuted(true)
        rig.feed(MUTED_SPEECH, 30) // private words, said while muted
        rig.feed(MUTED_QUIET, 10)
        rig.loop.setMuted(false)
        rig.utterance(POST_QUIET, POST_SPEECH) // starts well within the 1.2 s pre-roll of the muted words
        assertEquals(1, rig.uploads.size)
        val amps = amplitudes(rig.uploads.single())
        assertTrue(POST_SPEECH in amps)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amps)
    }

    @Test fun speechDuringATransientFocusHoldIsNotInTheFirstUtteranceAfterResume() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.feed(PRE_QUIET, 20)
        rig.loop.onAudioFocusLost(permanent = false) // a phone call rings: hold
        assertEquals(VoiceCallLoop.Phase.HOLD, rig.loop.phase)
        rig.feed(MUTED_SPEECH, 30) // talking on the phone call
        rig.feed(MUTED_QUIET, 10)
        rig.loop.onAudioFocusGained()
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun blocksRecordedBeforeUnmuteButReadAfterAreDropped() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.loop.setMuted(true)
        rig.feed(MUTED_QUIET, 10)
        rig.loop.setMuted(false)
        val unmutedAt = rig.clock
        // The recorder's buffer still holds 240 ms recorded while muted; the mic thread reads it after the unmute.
        rig.feed(MUTED_SPEECH, 8) { i -> unmutedAt - (8 - i) * blockNanos }
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun muteMidUtteranceDropsTheCaptureAndNothingCarriesOver() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.feed(PRE_QUIET, 20)
        rig.feed(PRE_SPEECH, 15)
        assertEquals(VoiceCallLoop.Phase.CAPTURING, rig.loop.phase)
        rig.loop.setMuted(true)
        rig.feed(MUTED_SPEECH, 20)
        rig.feed(MUTED_QUIET, 45)
        assertTrue(rig.uploads.isEmpty())
        rig.loop.setMuted(false)
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun aPauseIsNeverOverwrittenAndNoUtteranceHoldsAudioFromBeforeItsArm() {
        // The mic thread feeds as fast as it can while the main thread arms and pauses at random; every block's
        // first two samples carry its index, so each utterance can be traced back frame by frame.
        val mic = MicCapture()
        val clock = AtomicLong(0)
        val armedAt = ConcurrentHashMap<Long, Long>()
        val done = AtomicBoolean(false)
        val utterances = mutableListOf<MicCapture.Event.Utterance>()
        val reader = Thread {
            while (!done.get()) {
                val idx = clock.getAndIncrement()
                val amp = if (idx % 100 in 30..49) 3000 else 40
                val f = frame(amp)
                f[0] = (idx % 256).toShort(); f[1] = ((idx / 256) % 256).toShort(); f[2] = ((idx / 65536) % 256).toShort()
                val e = mic.feed(f, idx * blockNanos, playing = false)
                if (e is MicCapture.Event.Utterance) synchronized(utterances) { utterances += e }
                java.util.concurrent.locks.LockSupport.parkNanos(20_000)
            }
        }
        reader.start()
        val rnd = java.util.Random(7)
        var token = 0L
        repeat(200) {
            token++
            armedAt[token] = clock.get()
            mic.arm(token, armedAt.getValue(token) * blockNanos)
            Thread.sleep(rnd.nextInt(20).toLong())
            token++
            mic.pause(token)
            Thread.sleep(rnd.nextInt(3).toLong())
        }
        done.set(true)
        reader.join()
        assertTrue("the stress run produced no utterance", utterances.isNotEmpty())
        for (u in utterances) {
            assertTrue("utterance under a pause token", u.token % 2 == 1L)
            val since = armedAt.getValue(u.token)
            val samples = (0 until (u.wav.size - 44) / 2).map { i ->
                ((u.wav[44 + 2 * i].toInt() and 0xff) or (u.wav[45 + 2 * i].toInt() shl 8)).toShort().toInt()
            }
            for (start in samples.indices step blockSamples) {
                val idx = (samples[start] and 0xff) + (samples[start + 1] and 0xff) * 256L + (samples[start + 2] and 0xff) * 65536L
                assertTrue("frame $idx predates its arm at $since", idx >= since)
            }
        }
    }
}
