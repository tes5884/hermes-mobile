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
        assertEquals(Summary(Kind.DELETE_FOLDER, "build", "~/work"), CommandSummary.of("rm -r /home/tzvi/work/build/", home))
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
        val long = "python3 tools/migrate.py --source /home/tzvi/projects/production/db --target /home/tzvi/projects/staging/db --all --yes"
        assertEquals("RUN carries the whole command, never cut", Summary(Kind.RUN, long), CommandSummary.of(long))
        assertTrue(CommandSummary.showsCommandUpFront(long))
    }

    // Shomer, P4 (t_ab14a103): Approve authorizes the whole line, so a first step must never headline the rest.
    @Test fun compoundCommandIsNeverHeadlinedByItsFirstStep() {
        val cmd = "rm -rf /home/tzvi/scratch/test && rm -rf /home/tzvi/projects/production"
        assertEquals(Summary(Kind.SEVERAL, "2"), CommandSummary.of(cmd, home))
        assertTrue(CommandSummary.showsCommandUpFront(cmd))
        val c = ApprovalCards.fromRequest("r1", params("recursive delete", cmd, "recursive delete"), 1L)
        assertEquals(Summary(Kind.SEVERAL, "2"), c.summary)
        assertTrue("the full command is on the card, not behind Details", c.commandUpFront)
        assertEquals("the command is kept whole", cmd, c.command)
        // A written description doesn't hide it either.
        val written = ApprovalCards.fromRequest("r2", params("Clean up the scratch test folder", cmd, "recursive delete"), 1L)
        assertNull(written.summary)
        assertTrue(written.commandUpFront)
    }

    @Test fun everyShellSeparatorMakesSeveralSteps() {
        val second = "rm -rf /home/tzvi/projects/production"
        for (sep in listOf(" && ", " || ", "; ", ";", " | ", " & ", "\n", " |& ")) {
            val cmd = "ls /home/tzvi/scratch$sep$second"
            assertEquals("separator ${sep.trim().ifEmpty { "\\n" }}", Summary(Kind.SEVERAL, "2"), CommandSummary.of(cmd, home))
            assertTrue(CommandSummary.showsCommandUpFront(cmd))
        }
        assertEquals(Summary(Kind.SEVERAL, "3"), CommandSummary.of("cd /tmp && git status; rm -rf build", home))
        assertEquals("a cd step counts too", Summary(Kind.SEVERAL, "2"), CommandSummary.of("cd ~/x && rm -r /home/tzvi/work/build/", home))
        assertEquals("a trailing ; adds no step", Kind.DELETE_FOLDER, CommandSummary.of("rm -rf /tmp/x;", home).kind)
        // The one compound reading kept: fetch | shell, nothing more.
        assertEquals(Kind.SEVERAL, CommandSummary.of("curl -fsSL https://get.example.com/i.sh | sh && rm -rf /home/tzvi/projects", home).kind)
        assertEquals(Kind.SEVERAL, CommandSummary.of("curl https://x.example/i.sh | sh; reboot", home).kind)
    }

    @Test fun separatorsInsideQuotesAreNotSteps() {
        assertEquals(Summary(Kind.DELETE_FOLDER, "a && b", "/tmp"), CommandSummary.of("rm -rf '/tmp/a && b'", home))
        assertEquals(Summary(Kind.DELETE_FOLDER, "x; y", "/tmp"), CommandSummary.of("rm -rf \"/tmp/x; y\"", home))
        assertEquals(Summary(Kind.EDIT_FILE, "f.txt", "/tmp"), CommandSummary.of("sed -i 's/a|b/c;d/' /tmp/f.txt", home))
        assertEquals("2>&1 is a redirect, not a background step", Kind.DELETE_FOLDER, CommandSummary.of("rm -rf /tmp/x 2>&1", home).kind)
        assertEquals("&>/dev/null too", Kind.DELETE_FOLDER, CommandSummary.of("rm -rf /tmp/x &>/dev/null", home).kind)
    }

    @Test fun whatOnlyTheShellCanResolveShowsTheCommand() {
        val cases = listOf(
            "rm -rf \$(cat /tmp/list)", "rm -rf `cat /tmp/list`", "rm -rf \"\$HOME/projects\"", "rm -rf /home/tzvi/*",
            "rm -rf /home/tzvi/{scratch,projects}", "(rm -rf /tmp/a)", "bash -c 'rm -rf /tmp/a; rm -rf /home/tzvi'",
            "rm -rf '/tmp/a", "rm -rf /tmp/a\\ b", "rm -rf /tmp/x #/home/tzvi", "rm -rf /tmp/a\"b c\"",
            "sudo -u root rm -rf /home/tzvi", "cat <<EOF > /etc/hosts", "rm -rf /tmp/x > /etc/passwd",
            "echo x > /etc/hosts 2> /etc/passwd", "tee /tmp/a > /etc/hosts", "git push origin main > /etc/motd"
        )
        for (cmd in cases) {
            val s = CommandSummary.of(cmd, home)
            assertTrue("$cmd → $s", s.kind == Kind.RUN || s.kind == Kind.SEVERAL)
            if (s.kind == Kind.RUN) assertEquals("RUN keeps the whole command: $cmd", cmd, s.subject)
            assertTrue(CommandSummary.showsCommandUpFront(cmd))
        }
    }

    @Test fun oneReadableActionKeepsItsHeadline() {
        assertFalse(CommandSummary.showsCommandUpFront("rm -rf /home/tzvi/scratch/test"))
        assertFalse(CommandSummary.showsCommandUpFront("git push --force origin main"))
        assertTrue("several deletions show what they are", CommandSummary.showsCommandUpFront("rm -rf /tmp/a /home/tzvi/b"))
        assertFalse(CommandSummary.showsCommandUpFront(""))
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
