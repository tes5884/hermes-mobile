package com.nousresearch.hermesmobile.data

/** Voice notes in rooms: bots read text, not audio, so a note is the audio file plus its transcript. */
object VoiceNotes {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_MS = 10L * 60 * 1000
    const val WARN_MS = 9L * 60 * 1000 + 30_000
    const val MIN_HOLD_MS = 1000L
    const val BITRATE = 32_000
    const val SAMPLE_RATE = 16_000
    const val TRANSCRIBE_TIMEOUT_S = 180L
    const val FAILED_TEXT = "(voice note, couldn't transcribe)"
    private val NAME = Regex("""^voice-note-(\d+)s-\d+\.m4a$""")

    /** The duration rides in the file name so every client can show it without downloading the audio. */
    fun fileName(durationMs: Long, nowMs: Long): String = "voice-note-${((durationMs + 500) / 1000).coerceAtLeast(1)}s-$nowMs.m4a"

    fun durationSeconds(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    fun isVoiceNote(a: GroupAttachment): Boolean = a.mime.startsWith("audio/") || NAME.matches(a.name)

    fun messageText(transcript: String?): String = transcript?.trim().orEmpty().ifBlank { FAILED_TEXT }

    fun clock(totalSeconds: Int): String = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)

    /** Hold shorter than this sends nothing and shows the "Hold to record" tooltip. */
    fun tooShort(heldMs: Long): Boolean = heldMs < MIN_HOLD_MS

    /** The timer turns red from 9:30. */
    fun nearLimit(elapsedMs: Long): Boolean = elapsedMs >= WARN_MS

    /** Slide left past a third of the width cancels. dx is negative to the left. */
    fun isCancelDrag(dxPx: Float, widthPx: Float): Boolean = widthPx > 0f && -dxPx > widthPx / 3f

    /** Slide up (dy negative) past this many px locks the recording. */
    fun isLockDrag(dyPx: Float, thresholdPx: Float): Boolean = -dyPx > thresholdPx

    /** getMaxAmplitude (0..32767) to a 0..1 bar height; log-ish so quiet speech still shows, silence is 0. */
    fun level(maxAmplitude: Int): Float {
        if (maxAmplitude <= 120) return 0f
        val db = 20.0 * Math.log10(maxAmplitude / 32767.0) // about -50..0
        return ((db + 45.0) / 45.0).coerceIn(0.0, 1.0).toFloat()
    }

    /** Next window of bar levels: newest appended on the right, oldest dropped. */
    fun pushLevel(levels: List<Float>, level: Float, size: Int = 40): List<Float> = (levels + level).takeLast(size)
}
