package com.nousresearch.hermesmobile.features

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.nousresearch.hermesmobile.data.BargeDetector
import com.nousresearch.hermesmobile.data.BargePhase
import com.nousresearch.hermesmobile.data.CallMic
import com.nousresearch.hermesmobile.data.CallRouteKind
import com.nousresearch.hermesmobile.data.CallRoutes
import com.nousresearch.hermesmobile.data.HermesHttpException
import com.nousresearch.hermesmobile.data.HermesRestClient
import com.nousresearch.hermesmobile.data.MicCapture
import com.nousresearch.hermesmobile.data.MicRecorder
import com.nousresearch.hermesmobile.data.VoiceCallLoop
import com.nousresearch.hermesmobile.data.VoiceCallUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The audio side of a live call with one bot, over the gateway relay (the profile's own STT/TTS providers; no keys
 * on the phone): `POST /api/audio/transcribe` for each utterance (16 kHz mono WAV) and `POST /api/audio/speak` per
 * sentence as the reply streams, the same per-sentence path Hermes Desktop uses for providers without a PCM stream
 * (AllModels has none, and the dashboard's speak-stream needs ffmpeg on its PATH to decode their MP3).
 *
 * Audio runs as a phone call: MODE_IN_COMMUNICATION, the VOICE_COMMUNICATION mic (platform echo cancellation) and
 * voice-call playback, routed to the earpiece, speaker, a wired or a Bluetooth headset; audio focus holds the call
 * while a phone call rings and resumes after. The microphone foreground service ([VoiceCallService]) keeps it
 * alive with the screen off. Everything touching [loop] runs on the main thread.
 */
class VoiceCallEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rest: HermesRestClient,
    private val profile: String,
    private val botLabel: String,
    private val host: Host
) : VoiceCallLoop.Effects {
    interface Host {
        fun submitVoiceTurn(text: String, interrupted: Boolean)
        fun interruptVoiceTurn()
        fun voiceCallChanged(ui: VoiceCallUi)
        fun voiceCallEnded(reason: VoiceCallLoop.EndReason)
    }

    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)
    val loop = VoiceCallLoop(this)
    private val lease = "mobile-call:${UUID.randomUUID()}"
    private var ui = VoiceCallUi(profile = profile, label = botLabel)
    private var tickJob: Job? = null
    @Volatile private var running = false

    private val voiceAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    // ---------------------------------------------------------------- lifecycle

    /** Starts the call. Returns false (nothing started) when the audio can't be had (e.g. a phone call is on). */
    fun start(turnRunning: Boolean): Boolean {
        if (!requestFocus()) return false
        running = true
        router.start()
        tickJob = scope.launch { while (isActive && running) { delay(250); loop.onTick() } }
        scope.launch(Dispatchers.IO) { runCatching { leaseOn("stt", true); leaseOn("tts", true) } }
        loop.start(turnRunning)
        publish()
        return true
    }

    fun hangUp() = loop.hangUp()
    fun chatClosed() = loop.chatClosed()
    fun setMuted(muted: Boolean) { loop.setMuted(muted); publish() }
    fun selectRoute(kind: CallRouteKind) { router.select(kind); publish() }

    private fun shutdown() {
        if (!running) return
        running = false
        tickJob?.cancel()
        speaker.stop()
        mic.pause()
        router.end()
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        scope.launch(NonCancellable + Dispatchers.IO) {
            withTimeoutOrNull(10_000) { runCatching { leaseOn("stt", false) }; runCatching { leaseOn("tts", false) } }
        }
    }

    private suspend fun leaseOn(kind: String, active: Boolean) {
        try { rest.scopedApi(profile, "/api/audio/$kind-lease", "POST", JSONObject().put("lease", lease).put("active", active), 60) }
        catch (e: HermesHttpException) { if (e.status !in listOf(404, 405, 501)) throw e }
    }

    private fun publish(notice: String? = ui.notice) {
        ui = ui.copy(phase = loop.phase, muted = loop.muted, route = router.current, routes = router.kinds, notice = notice)
        host.voiceCallChanged(ui)
    }

    // ---------------------------------------------------------------- loop effects (main thread)

    private var armToken = 0L

    override fun armMic() { armToken++; mic.arm(armToken) }
    override fun pauseMic() { armToken++; mic.pause() }
    override fun stopSpeech() = speaker.stop()
    override fun speak(sentence: String) = speaker.say(sentence)
    override fun interruptTurn() = host.interruptVoiceTurn()
    override fun submit(text: String, interrupted: Boolean) { publish(notice = null); host.submitVoiceTurn(text, interrupted) }
    override fun phaseChanged(phase: VoiceCallLoop.Phase) { if (running) publish(notice = if (phase == VoiceCallLoop.Phase.CAPTURING) null else ui.notice) }
    override fun notice(notice: VoiceCallLoop.Notice) = publish(notice = when (notice) {
        VoiceCallLoop.Notice.DIDNT_CATCH -> context.getString(com.nousresearch.hermesmobile.R.string.call_didnt_catch)
        VoiceCallLoop.Notice.TRANSCRIBE_FAILED -> context.getString(com.nousresearch.hermesmobile.R.string.call_stt_failed)
    })

    override fun ended(reason: VoiceCallLoop.EndReason) {
        shutdown()
        host.voiceCallEnded(reason)
    }

    override fun transcribe(wav: ByteArray, token: Long) {
        scope.launch {
            val text = try {
                val dataUrl = withContext(Dispatchers.Default) { "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP) }
                rest.scopedApi(profile, "/api/audio/transcribe", "POST",
                    JSONObject().put("data_url", dataUrl).put("mime_type", "audio/wav"), 90).optString("transcript").trim()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("HermesCall", "transcribe failed: ${e.message}"); null }
            loop.onTranscript(token, text)
        }
    }

    // ---------------------------------------------------------------- audio focus

    private var focusRequest: AudioFocusRequest? = null

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(voiceAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> loop.onAudioFocusLost(permanent = true)
                    // A phone call ringing or another call app: hold, resume when it gives the audio back.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { loop.onAudioFocusLost(permanent = false); publish() }
                    // A notification sound: keep talking.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
                    AudioManager.AUDIOFOCUS_GAIN -> { router.reapply(); loop.onAudioFocusGained(); publish() }
                }
            }, main)
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // ---------------------------------------------------------------- routing

    private val router = Router()

    private inner class Router {
        private var previousMode = AudioManager.MODE_NORMAL
        var current: CallRouteKind? = null
            private set
        var kinds: List<CallRouteKind> = emptyList()
            private set
        private val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }

        fun start() {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            kinds = available()
            CallRoutes.preferred(kinds)?.let { select(it) }
            am.registerAudioDeviceCallback(callback, main)
        }

        /** After a phone call the system put the mode back: take the call audio again. */
        fun reapply() {
            if (!running) return
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            current?.let { select(it) }
        }

        private fun refresh() {
            if (!running) return
            val before = kinds
            kinds = available()
            val next = CallRoutes.next(current, before, kinds)
            if (next != null && next != current) select(next)
            publish()
        }

        private fun kindOf(type: Int): CallRouteKind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallRouteKind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallRouteKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> CallRouteKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> CallRouteKind.BLUETOOTH
            else -> if (Build.VERSION.SDK_INT >= 31 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_HEARING_AID)) CallRouteKind.BLUETOOTH else null
        }

        private fun available(): List<CallRouteKind> {
            val found = if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices.mapNotNull { kindOf(it.type) }
            else listOf(CallRouteKind.EARPIECE, CallRouteKind.SPEAKER) +
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { kindOf(it.type) }
            return CallRouteKind.entries.filter { it in found }
        }

        @Suppress("DEPRECATION")
        fun select(kind: CallRouteKind) {
            if (Build.VERSION.SDK_INT >= 31) {
                val device = am.availableCommunicationDevices.firstOrNull { kindOf(it.type) == kind } ?: return
                if (am.setCommunicationDevice(device)) current = kind
            } else {
                if (kind == CallRouteKind.BLUETOOTH) {
                    am.isSpeakerphoneOn = false
                    am.startBluetoothSco(); am.isBluetoothScoOn = true
                } else {
                    if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
                    am.isSpeakerphoneOn = kind == CallRouteKind.SPEAKER
                }
                current = kind
            }
        }

        @Suppress("DEPRECATION")
        fun end() {
            runCatching { am.unregisterAudioDeviceCallback(callback) }
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            else {
                if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
                am.isSpeakerphoneOn = false
            }
            am.mode = previousMode
        }
    }

    // ---------------------------------------------------------------- speaking

    private val speaker = Speaker()

    /**
     * Sentences in order: each is synthesized by the gateway (one ahead of playback) and played as soon as the one
     * before it ends. [stop] cuts the sound at once and drops everything queued.
     */
    private inner class Speaker {
        @Volatile var playing = false
            private set
        private var generation = 0L
        private var texts: Channel<String>? = null
        private var job: Job? = null
        private var player: MediaPlayer? = null
        private var outstanding = 0
        private var started = false

        fun say(sentence: String) {
            val channel = texts ?: Channel<String>(Channel.UNLIMITED).also { texts = it; launch(it, generation) }
            outstanding++
            channel.trySend(sentence)
        }

        private fun launch(channel: Channel<String>, gen: Long) {
            val audio = Channel<File?>(capacity = 1)
            started = false
            job = scope.launch {
                launch {
                    for (text in channel) {
                        val file = runCatching { synthesize(text) }.onFailure {
                            if (it is kotlinx.coroutines.CancellationException) throw it
                            android.util.Log.w("HermesCall", "speak failed: ${it.message}")
                        }.getOrNull()
                        audio.send(file)
                    }
                    audio.close()
                }
                for (file in audio) {
                    if (gen != generation) { file?.delete(); continue }
                    if (file != null) play(file, gen)
                    finishOne(gen)
                }
            }
        }

        private fun finishOne(gen: Long) {
            if (gen != generation) return
            outstanding = (outstanding - 1).coerceAtLeast(0)
            if (outstanding == 0) { started = false; loop.onPlaybackIdle() }
        }

        private suspend fun synthesize(text: String): File {
            val result = rest.scopedApi(profile, "/api/audio/speak", "POST", JSONObject().put("text", text.take(4_000)), 90)
            val dataUrl = result.optString("data_url")
            require(dataUrl.startsWith("data:") && dataUrl.contains(",")) { "no audio" }
            val ext = when (result.optString("mime_type").substringBefore(';')) {
                "audio/ogg" -> ".ogg"; "audio/wav" -> ".wav"; "audio/flac" -> ".flac"; else -> ".mp3"
            }
            return withContext(Dispatchers.IO) {
                File.createTempFile("hermes-call-", ext, context.cacheDir).also {
                    it.writeBytes(Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT))
                }
            }
        }

        private suspend fun play(file: File, gen: Long) {
            try {
                suspendCancellableCoroutine { cont ->
                    val mp = MediaPlayer()
                    player = mp
                    fun done() {
                        playing = false
                        if (player === mp) player = null
                        runCatching { mp.release() }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation { main.post { done() } }
                    try {
                        mp.setAudioAttributes(voiceAttributes)
                        mp.setDataSource(file.path)
                        mp.setOnPreparedListener {
                            if (gen != generation) { done(); return@setOnPreparedListener }
                            playing = true
                            it.start()
                            if (!started) { started = true; loop.onPlaybackStarted() }
                        }
                        mp.setOnCompletionListener { done() }
                        mp.setOnErrorListener { _, _, _ -> done(); true }
                        mp.prepareAsync()
                    } catch (e: Exception) { done() }
                }
            } finally { file.delete() }
        }

        fun stop() {
            generation++
            texts?.close()
            texts = null
            job?.cancel()
            job = null
            player?.let { runCatching { it.stop() }; runCatching { it.release() } }
            player = null
            playing = false
            outstanding = 0
        }
    }

    // ---------------------------------------------------------------- listening

    private val mic = Mic()

    /**
     * The microphone is on only while the call listens ([CallMic]): every arm opens a NEW AudioRecord (fresh
     * pre-roll, barge detector, capture); every pause (mute, hold, transcribing, end) stops it at once and its reader
     * releases it and drops every buffer, so the system mic indicator goes off and nothing said meanwhile is
     * recorded. Blocks go through [MicCapture]: the barge detector (the speaker tells it whether the bot is audible),
     * the 1.2 s pre-roll so the first syllable isn't lost, capture until quiet. Events carry the arm token they
     * started under, so one overtaken by a newer arm/pause on the main thread is dropped.
     */
    private inner class Mic {
        private val rate = 16_000
        private val block = rate * BargeDetector.BLOCK_MS / 1000
        private val calls = CallMic(
            open = { AndroidRecorder() },
            blockSamples = block,
            playing = { speaker.playing },
            emit = { t, e ->
                when (e) {
                    is MicCapture.Event.SpeechStart -> {
                        val barge = e.phase == BargePhase.PLAYBACK
                        main.post { if (running && t == armToken) loop.onSpeechStart(barge) }
                    }
                    is MicCapture.Event.Utterance -> {
                        val wav = e.wav
                        main.post { if (running && t == armToken) loop.onUtterance(wav) }
                    }
                }
            },
            failed = { t, e ->
                android.util.Log.w("HermesCall", "mic failed: ${e.message}")
                main.post { if (running && t == armToken) loop.fail() }
            },
            rate = rate
        )

        fun arm(t: Long) = calls.arm(t)
        fun pause() = calls.pause()

        /** One AudioRecord (VOICE_COMMUNICATION + echo canceller + noise suppressor) for one listening window. */
        @android.annotation.SuppressLint("MissingPermission") // the call button asks for RECORD_AUDIO first
        private inner class AndroidRecorder : MicRecorder {
            private val rec: AudioRecord
            private val aec: AcousticEchoCanceler?
            private val ns: NoiseSuppressor?

            init {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, block * 2 * 8))
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("microphone unavailable") }
                aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
                ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            }

            override fun start() = rec.startRecording()
            override fun read(buf: ShortArray): Int = rec.read(buf, 0, buf.size)
            override fun stop() = rec.stop()
            override fun release() {
                runCatching { aec?.release() }; runCatching { ns?.release() }
                rec.release()
            }
        }
    }
}
