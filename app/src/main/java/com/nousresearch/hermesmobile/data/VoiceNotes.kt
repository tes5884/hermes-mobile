package com.nousresearch.hermesmobile.data

/** Voice notes in rooms: bots read text, not audio, so a note is the audio file plus its transcript. */
object VoiceNotes {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_MS = 5L * 60 * 1000
    const val FAILED_TEXT = "(voice note, couldn't transcribe)"
    private val NAME = Regex("""^voice-note-(\d+)s-\d+\.m4a$""")

    /** The duration rides in the file name so every client can show it without downloading the audio. */
    fun fileName(durationMs: Long, nowMs: Long): String = "voice-note-${((durationMs + 500) / 1000).coerceAtLeast(1)}s-$nowMs.m4a"

    fun durationSeconds(name: String): Int? = NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

    fun isVoiceNote(a: GroupAttachment): Boolean = a.mime.startsWith("audio/") || NAME.matches(a.name)

    fun messageText(transcript: String?): String = transcript?.trim().orEmpty().ifBlank { FAILED_TEXT }

    fun clock(totalSeconds: Int): String = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
