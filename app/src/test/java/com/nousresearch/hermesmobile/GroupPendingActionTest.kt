package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.GroupPendingAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupPendingActionTest {
    @Test fun readsCommandAndDescriptionFromNestedApproval() {
        val a = GroupPendingAction.fromJson(JSONObject("""{"kind":"approval","member_id":"ezra","task_id":"t1",
            "request_id":"r1","approval":{"description":"Run focused tests","command":"pytest -q tests/focused","choices":["once","deny"]}}"""))
        assertEquals("pytest -q tests/focused", a.command)
        assertEquals("Run focused tests", a.description)
        assertEquals("r1", a.requestId)
    }

    @Test fun fallsBackToTopLevelFields() {
        val a = GroupPendingAction.fromJson(JSONObject("""{"kind":"approval","command":"ls","description":"List"}"""))
        assertEquals("ls", a.command)
        assertEquals("List", a.description)
    }
}
