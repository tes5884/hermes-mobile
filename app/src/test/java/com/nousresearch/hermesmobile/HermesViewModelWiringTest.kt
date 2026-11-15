package com.nousresearch.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the "one place" wiring of the chat-change reconcile in HermesViewModel (Tzvi, 2026-11-15), so a later change
 * can't quietly add a path that skips it: the screen state has a single writer (the uiState setter, which reconciles on
 * every change of what is on screen), every navigation generation step goes through navigate(), and no path drops the
 * "Talk to …" note or sorts approvals on its own.
 */
class HermesViewModelWiringTest {
    private val source: String = listOf(
        File("src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt"),
        File("app/src/main/java/com/nousresearch/hermesmobile/HermesViewModel.kt")
    ).first { it.exists() }.readText()

    private fun count(pattern: String) = Regex(pattern).findAll(source).count()

    /** The body of `fun name(` (first match), by brace matching. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return source.substring(open, i + 1) } }
        }
        error("unbalanced $signature")
    }

    @Test fun theScreenStateHasOneWriterAndItReconciles() {
        val setter = body("var uiState: HermesUiState")
        assertTrue(setter.contains("reconcileChatChange()"))
        // The backing state is written by the setter and by the reconcile itself (closing the channels list), nowhere else.
        assertEquals(2, count("""uiStateValue\s*="""))
        assertTrue(body("private fun reconcileChatChange()").contains("uiStateValue = uiState.copy(channelsListOpen = false)"))
    }

    @Test fun everyNavigationStepGoesThroughNavigate() {
        assertEquals(1, count("""\+\+navigationGeneration|navigationGeneration\s*\+="""))
        assertTrue(body("private fun navigate()").contains("++navigationGeneration"))
        assertTrue(body("private fun navigate()").contains("reconcileChatChange()"))
        for (path in listOf(
            "fun disconnect(", "fun closeActiveChat(", "fun newWorkspaceChat(", "fun openSession(",
            "private fun openBot(profile: HermesProfile, talk", "fun branchSession(", "fun createBot(", "fun openGroup(", "fun showChannels("
        )) assertTrue("$path must call navigate()", body(path).contains("navigate()"))
    }

    @Test fun noPathDropsTheNoteOrSortsApprovalsOnItsOwn() {
        assertEquals("pendingTalk.drop() only at teardown", 1, count("""pendingTalk\.drop\(\)"""))
        assertTrue(body("override fun onCleared()").contains("pendingTalk.drop()"))
        assertFalse(source.contains("approvalRouter.reconcile("))
        assertFalse(source.contains("adoptInlineApprovals"))
        // Approvals are routed against the same view the reconcile uses.
        assertTrue(source.contains("approvalRouter.onRequest(request, chatViewOf(uiState, connectionEpoch).surface)"))
    }
}
