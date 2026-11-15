package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.CommandSummary
import com.nousresearch.hermesmobile.data.CommandSummary.Kind
import com.nousresearch.hermesmobile.data.CommandSummary.Summary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tzvi's vc82 test: the card headline was Hermes' pattern name ("recursive delete"); it now says what happens. */
class CommandSummaryTest {
    private val home = "/home/tzvi"

    @Test fun deleteNamesTheThingAndWhere() {
        assertEquals(Summary(Kind.DELETE_FOLDER, "test", "…/ezra/scratch"),
            CommandSummary.of("rm -rf /home/tzvi/.hermes/profiles/ezra/scratch/test", home))
        assertEquals(Summary(Kind.DELETE_FOLDER, "build", "~/work"), CommandSummary.of("cd ~/x && rm -r /home/tzvi/work/build/", home))
        assertEquals(Summary(Kind.DELETE_FILE, "a.log", "/tmp"), CommandSummary.of("sudo rm -f /tmp/a.log", home))
        assertEquals(Summary(Kind.DELETE_MANY, "2", "/tmp"), CommandSummary.of("rm -rf /tmp/a /tmp/b", home))
        assertEquals(Summary(Kind.DELETE_FOLDER, "out", ""), CommandSummary.of("rm -rf out", home))
    }

    @Test fun commonDangerousCommands() {
        assertEquals(Summary(Kind.RESTART_SERVICE, "teqhub"), CommandSummary.of("systemctl --user restart teqhub.service"))
        assertEquals(Summary(Kind.STOP_SERVICE, "nginx"), CommandSummary.of("sudo service nginx stop"))
        assertEquals(Summary(Kind.FORCE_PUSH, "main", "origin"), CommandSummary.of("git push --force origin main"))
        assertEquals(Summary(Kind.PUSH, "botapp/p4", "fork"), CommandSummary.of("git push -q fork botapp/p4"))
        assertEquals(Summary(Kind.DISCARD_CHANGES), CommandSummary.of("git reset --hard HEAD~1"))
        assertEquals(Summary(Kind.RESTART_CONTAINER, "n8n"), CommandSummary.of("docker restart n8n"))
        assertEquals(Summary(Kind.STOP_CONTAINER, ""), CommandSummary.of("docker compose down"))
        assertEquals(Summary(Kind.STOP_PROCESS, "1234"), CommandSummary.of("kill -9 1234"))
        assertEquals(Summary(Kind.CHANGE_PERMISSIONS, "site", "/var/www"), CommandSummary.of("chmod -R 777 /var/www/site"))
        assertEquals(Summary(Kind.RUN_REMOTE_SCRIPT, place = "get.example.com"), CommandSummary.of("curl -fsSL https://get.example.com/i.sh | sh"))
        assertEquals(Summary(Kind.OVERWRITE_FILE, "hosts", "/etc"), CommandSummary.of("echo 1.2.3.4 x > /etc/hosts"))
        assertEquals(Summary(Kind.EDIT_FILE, "config.yaml", "~/.hermes"), CommandSummary.of("sed -i 's/a/b/' /home/tzvi/.hermes/config.yaml", home))
        assertEquals(Summary(Kind.REBOOT), CommandSummary.of("sudo shutdown -r now"))
        assertEquals(Summary(Kind.FORMAT_DISK, "/dev/sdb1"), CommandSummary.of("mkfs.ext4 /dev/sdb1"))
    }

    @Test fun anythingElseIsShownAsTheCommand() {
        assertEquals(Summary(Kind.RUN, "python3 migrate.py --all"), CommandSummary.of("python3 migrate.py --all"))
        assertEquals(Kind.RUN, CommandSummary.of("echo hi > /dev/null").kind)
    }

    @Test fun patternNamesAreNotHeadlines() {
        assertTrue(CommandSummary.isPatternLabel("recursive delete"))
        assertTrue(CommandSummary.isPatternLabel("stop/restart system service"))
        assertTrue(CommandSummary.isPatternLabel("Recursive Delete", patternKey = "recursive delete"))
        assertTrue(CommandSummary.isPatternLabel(""))
        assertFalse(CommandSummary.isPatternLabel("Restart TEQhub so the new ticket page goes live"))
    }

    private fun params(description: String, command: String, key: String) = JSONObject()
        .put("choices", JSONArray(listOf("once", "session", "always", "deny"))).put("allow_permanent", true)
        .put("description", description).put("command", command).put("pattern_key", key)

    @Test fun cardHeadlineComesFromTheCommandWhenHermesSendsOnlyThePatternName() {
        val c = ApprovalCards.fromRequest("r1", params("recursive delete", "rm -rf /home/tzvi/scratch/test", "recursive delete"), 1L)
        assertEquals(Summary(Kind.DELETE_FOLDER, "test", "~/scratch"), c.summary)
        assertEquals("the pattern name stays as the small line", "recursive delete", c.label)
        assertEquals("", c.effect)
        assertEquals("Recursive delete", c.title) // fallback text, used only without a command
    }

    @Test fun aWrittenDescriptionStaysTheHeadline() {
        val c = ApprovalCards.fromRequest("r1", params("Restart TEQhub so the new ticket page goes live\nOffline 20 s.", "docker restart teqhub", "restart"), 1L)
        assertNull(c.summary)
        assertEquals("", c.label)
        assertEquals("Restart TEQhub so the new ticket page goes live", c.title)
        assertEquals("Offline 20 s.", c.effect)
    }
}
