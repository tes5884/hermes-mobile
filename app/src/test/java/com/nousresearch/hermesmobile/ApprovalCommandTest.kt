package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.ui.components.COMMAND_PREVIEW_LINES
import com.nousresearch.hermesmobile.ui.components.commandNeedsCollapse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCommandTest {
    @Test fun longCommandCollapses() {
        val cmd = (1..200).joinToString("\n") { "echo line $it" }
        assertTrue(commandNeedsCollapse(cmd))
        assertEquals(200, cmd.lines().size)
        assertEquals(8, COMMAND_PREVIEW_LINES)
    }

    @Test fun shortCommandStaysOpen() {
        assertFalse(commandNeedsCollapse("ls -la"))
        assertFalse(commandNeedsCollapse((1..8).joinToString("\n") { "x$it" }))
        assertTrue(commandNeedsCollapse((1..9).joinToString("\n") { "x$it" }))
    }
}
