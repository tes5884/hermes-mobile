package com.nousresearch.hermesmobile.data

/**
 * Voice notes in a 1:1 chat, drawn to Nova's mocks (2-chat-voice, 2b-recording): which sent turn is a note,
 * the bars its bubble draws, and what the bot chat's composer shows while recording.
 */
object VoiceBubbles {
    /** A sent turn that is one voice note: the audio's [name], its gateway [path] once known, and the transcript. */
    data class Note(val name: String, val path: String?, val seconds: Int, val transcript: String) {
        /** False when transcription failed and the turn carries only the fallback text. */
        val transcribed: Boolean get() = transcript.isNotBlank() && transcript != VoiceNotes.FAILED_TEXT
    }

    // The gateway may stage the upload under a longer name; the note's own name stays inside it.
    private val NOTE = Regex("""voice-note-(\d+)s-\d+\.m4a$""")

    /** Seconds from a voice note's file name (or a staged name that contains it); null for any other file. */
    fun seconds(name: String): Int? = NOTE.find(name.trimEnd('/').substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The voice note a user turn carries, or null. Only a turn whose single attachment is a note counts:
     * a note sent with other files keeps the plain chips, so nothing is hidden.
     */
    fun of(message: ChatMessage): Note? {
        if (message.role != "user") return null
        val paths = message.refs.map { TurnDisplay.refPath(it) }
        // A live bubble knows only the picked names; a stored turn knows refs (and the names lifted from them).
        val files = if (paths.isNotEmpty()) paths.map { it.trimEnd('/').substringAfterLast('/') } else message.attachments
        if (files.size != 1 || (message.attachments.size > 1)) return null
        val name = files.single()
        val seconds = seconds(name) ?: return null
        val path = paths.singleOrNull()?.takeIf { ChatAudio.path(it) != null }
        return Note(name, path, seconds, message.content.trim())
    }

    /**
     * A note's resting waveform: [count] bar heights in 0.25..1, the same for the same [seed] on every draw
     * (the audio isn't downloaded just to draw it), so the bubble doesn't flicker as the list recomposes.
     */
    fun bars(seed: String, count: Int = 28): List<Float> {
        var x = seed.hashCode().toLong() and 0xffffffffL
        if (x == 0L) x = 0x9e3779b9L
        return List(count) {
            // xorshift32: cheap and stable across devices.
            x = x xor ((x shl 13) and 0xffffffffL)
            x = x xor (x ushr 17)
            x = x xor ((x shl 5) and 0xffffffffL)
            .25f + (x % 1000L).toFloat() / 1000f * .75f
        }
    }

    /** How much of the note has played, 0..1, for filling the bars. */
    fun progress(positionMs: Int, durationMs: Int): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Bars at or before [progress] read as played. */
    fun played(index: Int, count: Int, progress: Float): Boolean = count > 0 && progress > 0f && (index + .5f) / count <= progress

    /** What the bot chat composer shows (one state at a time). */
    enum class Composer { IDLE, TYPING, RECORDING, LOCKED }

    fun composer(recording: Boolean, locked: Boolean, hasMessage: Boolean): Composer = when {
        recording && locked -> Composer.LOCKED
        recording -> Composer.RECORDING
        hasMessage -> Composer.TYPING
        else -> Composer.IDLE
    }

    /** The bot header's status line: recording beats everything (2b mock), then Needs your OK, then working. */
    enum class Header { LISTENING, NEEDS_OK, WORKING, ROLE }

    fun header(listening: Boolean, needsOk: Boolean, working: Boolean): Header = when {
        listening -> Header.LISTENING
        needsOk -> Header.NEEDS_OK
        working -> Header.WORKING
        else -> Header.ROLE
    }
}
