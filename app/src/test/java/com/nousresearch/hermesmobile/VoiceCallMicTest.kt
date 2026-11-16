package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BargeDetector
import com.nousresearch.hermesmobile.data.CallMic
import com.nousresearch.hermesmobile.data.MicCapture
import com.nousresearch.hermesmobile.data.MicRecorder
import com.nousresearch.hermesmobile.data.VoiceCallLoop
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The call's microphone is off while muted, on hold or transcribing (Tzvi, 2026-11-15; Shomer t_215dfd5f /
 * t_4c4fd4f4): the recorder is stopped and released, every buffer is dropped, and unmute/resume opens a new recorder
 * with a fresh pre-roll. The real loop + [CallMic] + [MicCapture] run on their real reader threads against a fake
 * recorder in a [Room]; the WAV that would be uploaded is decoded and checked sample by sample.
 */
class VoiceCallMicTest {
    private val blockSamples = 16_000 * BargeDetector.BLOCK_MS / 1000

    // Each phase of a test gets its own amplitude, so a decoded WAV shows where every sample came from.
    private val PRE_QUIET = 41; private val PRE_SPEECH = 4111
    private val MUTED_SPEECH = 5111; private val MUTED_QUIET = 51
    private val POST_QUIET = 32; private val POST_SPEECH = 3222

    /**
     * The air around the phone and every recorder the call opened. Speaking puts a frame into each recorder that is
     * recording, the way a microphone does; a stopped or released one hears nothing. A recorder hands back what it
     * buffered even after stop() (the worst case), so the call itself must discard it.
     */
    private inner class Room(private val tagged: Boolean = false) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait/notifyAll
        val lock = Object()
        val recorders = mutableListOf<Fake>() // under lock
        var spoken = 0L // under lock: index of the next frame said
        var maxRecordingAtOnce = 0 // under lock
        /** While true, recorders don't hand out what they hear (it piles up in their buffer). */
        var stall = false
            set(v) = synchronized(lock) { field = v; lock.notifyAll() }
        /** While true, a reader blocked in read() doesn't come back, even once its recorder is stopped. */
        var hold = false
            set(v) = synchronized(lock) { field = v; lock.notifyAll() }

        fun frame(amp: Int, idx: Long) = ShortArray(blockSamples) { i -> (if (i % 2 == 0) amp else -amp).toShort() }.also {
            if (tagged) { it[0] = (idx % 256).toShort(); it[1] = (idx / 256 % 256).toShort(); it[2] = (idx / 65536 % 256).toShort() }
        }

        fun say(amp: Int, count: Int) = synchronized(lock) {
            repeat(count) {
                val f = frame(amp, spoken++)
                for (r in recorders) if (r.recording) { r.buf.addLast(f); r.heard += amp }
            }
            lock.notifyAll()
        }

        fun open(token: Long): Fake = synchronized(lock) { Fake(token).also { recorders += it } }
        fun recordingNow() = synchronized(lock) { recorders.count { it.recording } }
        fun unreleased() = synchronized(lock) { recorders.count { !it.released } }
        fun count() = synchronized(lock) { recorders.size }
        fun heardByAny(amp: Int) = synchronized(lock) { recorders.any { amp in it.heard } }

        inner class Fake(val token: Long) : MicRecorder {
            var started = false; var stopped = false; var released = false; var waiting = false
            val buf = ArrayDeque<ShortArray>()
            val heard = mutableSetOf<Int>()
            val recording get() = started && !stopped && !released

            override fun start() = synchronized(lock) {
                check(!released && !stopped)
                started = true
                maxRecordingAtOnce = maxOf(maxRecordingAtOnce, recorders.count { it.recording })
            }

            override fun read(buf: ShortArray): Int = synchronized(lock) {
                while (hold || ((this.buf.isEmpty() || stall) && recording)) { waiting = true; lock.notifyAll(); lock.wait() }
                waiting = false
                val f = this.buf.removeFirstOrNull() ?: return -3 // AudioRecord.ERROR_INVALID_OPERATION once stopped
                f.copyInto(buf)
                f.size
            }

            override fun stop() = synchronized(lock) { stopped = true; lock.notifyAll() }
            override fun release() = synchronized(lock) { released = true; buf.clear(); lock.notifyAll() }
        }
    }

    /** VoiceCallEngine's wiring of loop ↔ mic, with the main thread played by the test thread. */
    private inner class Rig : VoiceCallLoop.Effects {
        val room = Room()
        var clockMs = 1_000L
        var armToken = 0L
        var armed = false
        val uploads = mutableListOf<ByteArray>()
        val events = ConcurrentLinkedQueue<Pair<Long, MicCapture.Event>>()
        val allEvents = ConcurrentLinkedQueue<Long>() // the token of every event the mic emitted, dropped or not
        val failures = ConcurrentLinkedQueue<Throwable>()
        val mic = CallMic(
            open = { room.open(it) }, blockSamples = blockSamples, playing = { false },
            emit = { t, e -> allEvents += t; events += t to e }, failed = { _, e -> failures += e })
        val loop = VoiceCallLoop(this) { clockMs }

        override fun armMic() { armToken++; armed = true; mic.arm(armToken) }
        override fun pauseMic() { armToken++; armed = false; mic.pause() }
        override fun stopSpeech() {}
        override fun speak(sentence: String) {}
        override fun interruptTurn() {}
        override fun transcribe(wav: ByteArray, token: Long) { uploads += wav }
        override fun submit(text: String, interrupted: Boolean) {}
        override fun phaseChanged(phase: VoiceCallLoop.Phase) {}
        override fun notice(notice: VoiceCallLoop.Notice) {}
        override fun ended(reason: VoiceCallLoop.EndReason) {}

        /** Wait until the mic is idle (every closed window's recorder released, the open one drained), then hand
         *  its events to the loop the way the main thread would; repeat while that changes anything. */
        fun settle() {
            while (true) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!idle()) {
                    check(System.currentTimeMillis() < deadline) { "the mic didn't settle" }
                    Thread.sleep(1)
                }
                val (t, e) = events.poll() ?: break
                if (t != armToken) continue
                when (e) {
                    is MicCapture.Event.SpeechStart -> loop.onSpeechStart(false)
                    is MicCapture.Event.Utterance -> loop.onUtterance(e.wav)
                }
            }
            assertTrue(failures.isEmpty())
        }

        private fun idle() = synchronized(room.lock) {
            room.recorders.all { it.released || (armed && it.token == armToken && it.started && it.waiting && it.buf.isEmpty()) } &&
                (!armed || room.recorders.any { it.token == armToken && it.started })
        }

        fun say(amp: Int, count: Int) { room.say(amp, count); clockMs += count * BargeDetector.BLOCK_MS.toLong(); settle() }

        /** The user says something: room calibration, speech, then the quiet that ends the utterance. */
        fun utterance(quiet: Int, speech: Int) { say(quiet, 20); say(speech, 15); say(quiet, 45) }

        /** The microphone is off: nothing recording, every recorder released. */
        fun assertMicOff() {
            assertEquals("a recorder is recording", 0, room.recordingNow())
            assertEquals("a recorder isn't released", 0, room.unreleased())
        }
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

    @Test fun mutedTheMicIsReleasedAndUnmuteOpensANewOneWithOnlyPostUnmuteAudio() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        assertEquals(1, rig.room.recordingNow())
        rig.say(PRE_QUIET, 20)
        rig.loop.setMuted(true)
        assertEquals("stopped at once on mute", 0, rig.room.recordingNow())
        rig.settle()
        rig.assertMicOff()
        rig.say(MUTED_SPEECH, 30) // private words, said while muted
        rig.say(MUTED_QUIET, 10)
        rig.assertMicOff()
        assertFalse("a recorder heard the muted speech", rig.room.heardByAny(MUTED_SPEECH))
        val before = rig.room.count()
        rig.loop.setMuted(false)
        rig.settle()
        assertEquals("unmute opens a new recorder", before + 1, rig.room.count())
        assertEquals(1, rig.room.recordingNow())
        rig.utterance(POST_QUIET, POST_SPEECH) // starts well within the 1.2 s pre-roll of the muted words
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
        assertEquals(1, rig.room.maxRecordingAtOnce)
    }

    @Test fun onHoldTheMicIsReleasedAndResumeOpensANewOneWithOnlyPostResumeAudio() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        rig.say(PRE_QUIET, 20)
        rig.loop.onAudioFocusLost(permanent = false) // a phone call rings: hold
        assertEquals(VoiceCallLoop.Phase.HOLD, rig.loop.phase)
        assertEquals("stopped at once on hold", 0, rig.room.recordingNow())
        rig.settle()
        rig.assertMicOff()
        rig.say(MUTED_SPEECH, 30) // talking on the phone call
        rig.say(MUTED_QUIET, 10)
        rig.assertMicOff()
        assertFalse(rig.room.heardByAny(MUTED_SPEECH))
        val before = rig.room.count()
        rig.loop.onAudioFocusGained()
        rig.settle()
        assertEquals(before + 1, rig.room.count())
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun whatTheRecorderStillHeldAtMuteIsDroppedNeverFed() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        val first = rig.armToken
        // The reader falls behind: 1.05 s of audio, speech included, sits unread in the recorder when the user mutes.
        rig.room.stall = true
        rig.room.say(PRE_QUIET, 20)
        rig.room.say(PRE_SPEECH, 15)
        rig.loop.setMuted(true)
        rig.room.stall = false // the stopped recorder now hands all of it back
        rig.settle()
        rig.assertMicOff()
        assertFalse("a block read after the mute reached the detector", rig.allEvents.contains(first))
        rig.loop.setMuted(false)
        rig.settle()
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun muteMidUtteranceDropsTheCaptureAndNothingCarriesOver() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        rig.say(PRE_QUIET, 20)
        rig.say(PRE_SPEECH, 15)
        assertEquals(VoiceCallLoop.Phase.CAPTURING, rig.loop.phase)
        rig.loop.setMuted(true)
        rig.settle()
        rig.assertMicOff()
        rig.say(MUTED_SPEECH, 20)
        rig.say(MUTED_QUIET, 45)
        assertTrue(rig.uploads.isEmpty())
        rig.loop.setMuted(false)
        rig.settle()
        rig.utterance(POST_QUIET, POST_SPEECH)
        assertEquals(1, rig.uploads.size)
        assertEquals(setOf(POST_QUIET, POST_SPEECH), amplitudes(rig.uploads.single()))
    }

    @Test fun aNewRecorderOpensOnlyAfterTheOldOneIsReleased() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        rig.room.hold = true // the old reader is slow to come back from read()
        rig.loop.setMuted(true)
        rig.loop.setMuted(false)
        Thread.sleep(150)
        assertEquals("opened a new recorder before the old one was released", 1, rig.room.count())
        assertEquals(0, rig.room.recordingNow())
        rig.room.hold = false
        rig.settle()
        assertEquals(2, rig.room.count())
        assertEquals(1, rig.room.recordingNow())
        assertEquals(1, rig.room.maxRecordingAtOnce)
    }

    @Test fun transcribingAndHangingUpAlsoTurnTheMicOff() {
        val rig = Rig()
        rig.loop.start(turnAlreadyRunning = false)
        rig.settle()
        rig.utterance(PRE_QUIET, PRE_SPEECH)
        assertEquals(VoiceCallLoop.Phase.TRANSCRIBING, rig.loop.phase)
        rig.assertMicOff()
        rig.loop.hangUp()
        rig.settle()
        rig.assertMicOff()
    }

    @Test fun underRandomMuteAndUnmuteEveryUtteranceIsFromItsOwnOpenWindowAndRecordersNeverOverlap() {
        // A talker speaks continuously on another thread while the test thread mutes and unmutes at random; every
        // frame carries its index, so each utterance can be traced back to when each of its frames was said.
        val room = Room(tagged = true)
        val window = ConcurrentHashMap<Long, LongRange>() // token -> frames said while it was open
        val utterances = ConcurrentLinkedQueue<Pair<Long, MicCapture.Event.Utterance>>()
        val mic = CallMic(open = { room.open(it) }, blockSamples = blockSamples, playing = { false },
            emit = { t, e -> if (e is MicCapture.Event.Utterance) utterances += t to e }, failed = { _, e -> throw AssertionError(e) })
        val done = AtomicBoolean(false)
        val talker = Thread {
            var i = 0L
            while (!done.get()) {
                room.say(if (i++ % 100 in 30..49) 3000 else 40, 1)
                java.util.concurrent.locks.LockSupport.parkNanos(20_000)
            }
        }
        talker.start()
        val rnd = java.util.Random(7)
        var token = 0L
        repeat(150) {
            token++
            val from = synchronized(room.lock) { room.spoken }
            mic.arm(token)
            Thread.sleep(rnd.nextInt(25).toLong())
            mic.pause()
            window[token] = from until synchronized(room.lock) { room.spoken }
            Thread.sleep(rnd.nextInt(3).toLong())
        }
        done.set(true)
        talker.join()
        assertTrue(mic.awaitReleased(5_000))
        assertEquals(0, room.unreleased())
        assertEquals("two recorders were recording at once", 1, room.maxRecordingAtOnce)
        assertTrue("the stress run produced no utterance", utterances.isNotEmpty())
        for ((t, u) in utterances) {
            val open = window.getValue(t)
            val samples = (0 until (u.wav.size - 44) / 2).map { i ->
                ((u.wav[44 + 2 * i].toInt() and 0xff) or (u.wav[45 + 2 * i].toInt() shl 8)).toShort().toInt()
            }
            for (start in samples.indices step blockSamples) {
                val idx = (samples[start] and 0xff) + (samples[start + 1] and 0xff) * 256L + (samples[start + 2] and 0xff) * 65536L
                assertTrue("frame $idx was said outside window $t ($open)", idx in open)
            }
        }
    }
}
