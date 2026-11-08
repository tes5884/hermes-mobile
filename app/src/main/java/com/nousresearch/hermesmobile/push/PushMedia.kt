package com.nousresearch.hermesmobile.push

/**
 * A file sent to the phone rides a notice as `MEDIA:<path>` lines (the server's push has no attachments).
 * The notice text hides them; the inbox shows each as a card that downloads the file and opens it.
 */
object PushMedia {
    private val LINE = Regex("(?im)^[ \\t]*MEDIA:[ \\t]*(\\S.*?)[ \\t]*$")

    /** Absolute paths named by `MEDIA:` lines, in order, without repeats. */
    fun paths(body: String): List<String> =
        LINE.findAll(body).map { it.groupValues[1].trim('`', '"', '\'') }.filter { it.startsWith("/") || it.startsWith("~/") }.distinct().toList()

    /** The notice text without its `MEDIA:` lines. */
    fun text(body: String): String = LINE.replace(body, "").replace(Regex("\\n{3,}"), "\n\n").trim()

    fun name(path: String): String = path.substringAfterLast('/').ifBlank { path }
}
