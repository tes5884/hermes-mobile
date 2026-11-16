package com.nousresearch.hermesmobile.data

import java.util.Calendar

/** The open Bot details page: whose, and its Library once loaded ([library] null = loading). */
data class BotDetailsState(
    val profile: String,
    val library: List<BotDetails.LibraryItem>? = null,
    val libraryError: String? = null
)

/**
 * Bot details (Nova's mocks 4 / 4b, 2026-11-15): one page per bot with Library, Routines, Cards and Settings. The rules
 * here are pure so they can be tested; the page and the view model only fetch and draw.
 */
object BotDetails {
    /** What a Library tile shows for a file: a photo thumbnail, or a document tile with the kind under the name. */
    enum class FileType { IMAGE, PDF, SHEET, TEXT, DOC, SLIDES, PAGE, AUDIO, VIDEO, ARCHIVE, OTHER }

    /** One file the bot sent in its Bot Chat. [epochSec] is when the message that delivered it was written. */
    data class LibraryItem(val path: String, val name: String, val type: FileType, val epochSec: Long)

    private val TYPES: Map<String, FileType> = buildMap {
        listOf("png", "jpg", "jpeg", "gif", "webp", "bmp").forEach { put(it, FileType.IMAGE) }
        put("pdf", FileType.PDF)
        listOf("csv", "tsv", "xlsx", "xls", "ods").forEach { put(it, FileType.SHEET) }
        listOf("txt", "md", "json", "yaml", "yml", "xml", "log").forEach { put(it, FileType.TEXT) }
        listOf("doc", "docx", "odt", "rtf", "epub").forEach { put(it, FileType.DOC) }
        listOf("ppt", "pptx", "odp", "key").forEach { put(it, FileType.SLIDES) }
        listOf("html", "htm", "svg").forEach { put(it, FileType.PAGE) }
        listOf("mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac", "aac").forEach { put(it, FileType.AUDIO) }
        listOf("mp4", "mov", "avi", "mkv", "webm", "3gp").forEach { put(it, FileType.VIDEO) }
        listOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa").forEach { put(it, FileType.ARCHIVE) }
    }

    fun fileType(name: String): FileType = TYPES[name.substringAfterLast('.', "").lowercase()] ?: FileType.OTHER

    /**
     * The Library: every file the bot delivered with `MEDIA:` (Hermes' "here is a file" convention, the same tags the
     * chat draws as pictures, players and file links) in its own replies and tool results, newest first, each path
     * once. The user's own uploads are not the bot's work and stay out.
     */
    fun library(messages: List<ChatMessage>): List<LibraryItem> {
        val seen = HashSet<String>()
        val items = ArrayList<LibraryItem>()
        for (message in messages.sortedByDescending { seconds(it.timestamp) }) {
            if (message.role != "assistant" && message.role != "tool") continue
            for (path in ChatMedia.paths(message.content)) {
                if (!seen.add(path)) continue
                val name = path.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: path
                items += LibraryItem(path, name, fileType(name), seconds(message.timestamp))
            }
        }
        return items
    }

    /** Stored rows carry seconds; a live row may carry milliseconds. */
    private fun seconds(timestamp: Long): Long = if (timestamp >= 10_000_000_000L) timestamp / 1000L else timestamp

    /** How many tiles the Library section shows before "See all" (the mock's 3 x 2 grid). */
    const val LIBRARY_PREVIEW = 6

    /** How many routines the section lists before "See all". */
    const val ROUTINES_PREVIEW = 3

    enum class Day { TODAY, YESTERDAY, OTHER }

    /** Today / yesterday / anything older, in the phone's time zone. [epochSec] 0 = unknown, shown as OTHER. */
    fun day(epochSec: Long, nowMs: Long = System.currentTimeMillis()): Day {
        if (epochSec <= 0L) return Day.OTHER
        val then = Calendar.getInstance().apply { timeInMillis = epochSec * 1000L }
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        if (sameDay(then, now)) return Day.TODAY
        now.add(Calendar.DAY_OF_YEAR, -1)
        return if (sameDay(then, now)) Day.YESTERDAY else Day.OTHER
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** The bot's routines: its own cron jobs, A–Z. */
    fun routines(jobs: List<CronJob>, profile: String): List<CronJob> =
        jobs.filter { it.profile == profile }.sortedBy { it.name.ifBlank { it.prompt }.lowercase() }

    /** The chip on a board card row (mock 4b): Working, Needs you, In review or To do. */
    enum class Chip { NEEDS_YOU, WORKING, REVIEW, TODO }

    fun chip(task: KanbanTask): Chip = when (task.status) {
        "blocked" -> Chip.NEEDS_YOU
        "running" -> Chip.WORKING
        "review" -> Chip.REVIEW
        else -> Chip.TODO
    }

    /**
     * The open cards assigned to the bot, the ones that need Tzvi first, then running, in review, and the rest; newest
     * first inside each. Done and archived cards are history, not work.
     */
    fun cards(snapshot: KanbanBoardSnapshot?, profile: String): List<KanbanTask> {
        val tasks = snapshot?.columns.orEmpty().flatMap { it.tasks }
            .filter { it.assignee.equals(profile, ignoreCase = true) && it.status !in setOf("done", "archived") }
            .distinctBy { it.id }
        return tasks.sortedWith(compareBy<KanbanTask> { chip(it).ordinal }.thenByDescending { maxOf(it.startedAt, it.createdAt) })
    }

    /**
     * The open Bot details page belongs to one bot's chat: it stays open only while the Chat page shows that bot's
     * chat. Anything else (another page, another chat, no chat) closes it.
     */
    fun stillOpen(detailsProfile: String?, chatPage: Boolean, chatProfile: String?, botChat: Boolean): Boolean =
        detailsProfile != null && chatPage && botChat && chatProfile == detailsProfile
}
