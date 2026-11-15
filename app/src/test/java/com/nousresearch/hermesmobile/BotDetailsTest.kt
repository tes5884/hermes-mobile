package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.BotDetails
import com.nousresearch.hermesmobile.data.BotDetails.Chip
import com.nousresearch.hermesmobile.data.BotDetails.FileType
import com.nousresearch.hermesmobile.data.ChatMedia
import com.nousresearch.hermesmobile.data.ChatMessage
import com.nousresearch.hermesmobile.data.CronJob
import com.nousresearch.hermesmobile.data.KanbanBoardSnapshot
import com.nousresearch.hermesmobile.data.KanbanColumn
import com.nousresearch.hermesmobile.data.KanbanTask
import com.nousresearch.hermesmobile.push.PushQuiet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BotDetailsTest {
    @Test fun mediaPathsAreTheOnesTheChatLinks() {
        assertEquals(listOf("/srv/out/report.pdf", "/tmp/cam 1.png"),
            ChatMedia.paths("Here it is MEDIA:/srv/out/report.pdf\nAnd MEDIA:\"/tmp/cam 1.png\""))
        assertEquals(emptyList<String>(), ChatMedia.paths("no files here"))
        // render() is unchanged by the refactor.
        assertEquals("![cam.png](</tmp/cam.png>)", ChatMedia.render("MEDIA:/tmp/cam.png"))
    }

    @Test fun libraryIsTheBotsDeliveriesNewestFirstEachOnce() {
        val messages = listOf(
            ChatMessage(role = "assistant", content = "Backup report MEDIA:/srv/backup.pdf", timestamp = 100),
            ChatMessage(role = "user", content = "MEDIA:/home/me/upload.png", timestamp = 150),
            ChatMessage(role = "tool", content = "{\"ok\":true} MEDIA:/tmp/garage.jpg", timestamp = 200),
            ChatMessage(role = "assistant", content = "again MEDIA:/srv/backup.pdf and MEDIA:/srv/list.xlsx", timestamp = 1_763_200_000_000L)
        )
        val items = BotDetails.library(messages)
        assertEquals(listOf("/srv/backup.pdf", "/srv/list.xlsx", "/tmp/garage.jpg"), items.map { it.path })
        assertEquals(listOf(FileType.PDF, FileType.SHEET, FileType.IMAGE), items.map { it.type })
        assertEquals("milliseconds are read as seconds", 1_763_200_000L, items.first().epochSec)
        assertEquals("backup.pdf", items.first().name)
    }

    @Test fun fileTypes() {
        assertEquals(FileType.TEXT, BotDetails.fileType("rules.md"))
        assertEquals(FileType.AUDIO, BotDetails.fileType("note.M4A"))
        assertEquals(FileType.OTHER, BotDetails.fileType("blob"))
    }

    @Test fun dayIsTodayYesterdayOrOther() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15, 12, 0, 0) }.timeInMillis
        assertEquals(BotDetails.Day.TODAY, BotDetails.day(now / 1000 - 3600, now))
        assertEquals(BotDetails.Day.YESTERDAY, BotDetails.day(now / 1000 - 86_400, now))
        assertEquals(BotDetails.Day.OTHER, BotDetails.day(now / 1000 - 3 * 86_400, now))
        assertEquals(BotDetails.Day.OTHER, BotDetails.day(0, now))
    }

    private fun job(name: String, profile: String) = CronJob.fromJson(JSONObject().put("id", name).put("name", name).put("profile", profile).put("enabled", true))

    @Test fun routinesAreTheBotsOwnAToZ() {
        val jobs = listOf(job("Disk space watch", "otto"), job("Inbox", "ezra"), job("backup check", "otto"))
        assertEquals(listOf("backup check", "Disk space watch"), BotDetails.routines(jobs, "otto").map { it.name })
    }

    private fun task(id: String, status: String, assignee: String?, created: Long = 0) =
        KanbanTask(id = id, title = id, status = status, assignee = assignee, createdAt = created)

    @Test fun cardsAreTheOpenOnesAssignedNeedsYouFirst() {
        val snapshot = KanbanBoardSnapshot(listOf(
            KanbanColumn("todo", listOf(task("t1", "todo", "otto", 5), task("t2", "todo", "ezra"))),
            KanbanColumn("running", listOf(task("t3", "running", "Otto"))),
            KanbanColumn("blocked", listOf(task("t4", "blocked", "otto"))),
            KanbanColumn("review", listOf(task("t6", "review", "otto"))),
            KanbanColumn("done", listOf(task("t5", "done", "otto")))
        ))
        val cards = BotDetails.cards(snapshot, "otto")
        assertEquals(listOf("t4", "t3", "t6", "t1"), cards.map { it.id })
        assertEquals(listOf(Chip.NEEDS_YOU, Chip.WORKING, Chip.REVIEW, Chip.TODO), cards.map(BotDetails::chip))
        assertEquals(emptyList<KanbanTask>(), BotDetails.cards(null, "otto"))
    }

    @Test fun detailsStaysOpenOnlyOverItsBotsChat() {
        assertTrue(BotDetails.stillOpen("otto", chatPage = true, chatProfile = "otto", botChat = true))
        assertFalse(BotDetails.stillOpen("otto", chatPage = false, chatProfile = "otto", botChat = true))
        assertFalse(BotDetails.stillOpen("otto", chatPage = true, chatProfile = "ezra", botChat = true))
        assertFalse(BotDetails.stillOpen("otto", chatPage = true, chatProfile = "otto", botChat = false))
        assertFalse(BotDetails.stillOpen(null, chatPage = true, chatProfile = "otto", botChat = true))
    }

    @Test fun aQuietBotRingsOnlyWhenItNeedsYou() {
        for (type in listOf("approval_required", "turn_failed", "reminder", "test")) assertTrue(type, PushQuiet.rings(type, quiet = true))
        for (type in listOf("turn_completed", "cron_delivery", "")) assertFalse(type, PushQuiet.rings(type, quiet = true))
        for (type in listOf("approval_required", "turn_completed", "cron_delivery")) assertTrue("not quiet = as before", PushQuiet.rings(type, quiet = false))
        assertEquals("c1|otto", PushQuiet.key("c1", "otto"))
    }
}
