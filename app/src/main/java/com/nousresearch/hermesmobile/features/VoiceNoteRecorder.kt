package com.nousresearch.hermesmobile.features

import android.content.Context
import android.media.MediaRecorder
import com.nousresearch.hermesmobile.data.VoiceNotes
import java.io.File

/** Records one voice note (m4a, 16 kHz mono AAC at 32 kbps, <= 10 min) for a room. Not thread-safe; drive it from the UI thread. */
class VoiceNoteRecorder(private val context: Context, private val onLimit: () -> Unit) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    val active: Boolean get() = recorder != null
    fun elapsedMs(): Long = if (recorder == null) 0 else System.currentTimeMillis() - startedAt

    /** Peak amplitude since the last call (0..32767), for the live waveform. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    fun start() {
        if (recorder != null) return
        val f = File.createTempFile("hermes-note-", ".m4a", context.cacheDir)
        @Suppress("DEPRECATION")
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(VoiceNotes.SAMPLE_RATE); r.setAudioEncodingBitRate(VoiceNotes.BITRATE)
            r.setMaxDuration(VoiceNotes.MAX_MS.toInt()); r.setMaxFileSize(VoiceNotes.MAX_BYTES)
            r.setOutputFile(f.path)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()
            }
            r.prepare(); r.start()
        } catch (e: Throwable) {
            runCatching { r.release() }; f.delete(); throw e
        }
        recorder = r; file = f; startedAt = System.currentTimeMillis()
    }

    /** Stops and returns the file + its length in ms, or null if nothing usable was recorded. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val f = file
        val ms = elapsedMs()
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || ms < 500) { f?.delete(); return null }
        return f to ms
    }

    fun cancel() {
        val r = recorder
        val f = file
        recorder = null; file = null
        if (r != null) { runCatching { r.stop() }; runCatching { r.release() } }
        f?.delete()
    }
}
