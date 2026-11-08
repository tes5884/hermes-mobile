package com.nousresearch.hermesmobile

import com.nousresearch.hermesmobile.data.GroupAttachments
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupAttachmentsTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2)
    private val pdf = "%PDF-1.4 x".toByteArray()

    @Test fun classifyDeclaresWhatTheBytesAre() {
        assertEquals("image" to "image/png", GroupAttachments.classify("a.jpg", "image/jpeg", png))
        assertEquals("file" to "application/octet-stream", GroupAttachments.classify("a.heic", "image/heic", byteArrayOf(0, 0, 0, 1)))
        assertEquals("pdf" to "application/pdf", GroupAttachments.classify("a.pdf", "application/octet-stream", pdf))
        assertEquals("file" to "application/octet-stream", GroupAttachments.classify("a.pdf", "application/pdf", "nope".toByteArray()))
        assertEquals("file" to "text/csv", GroupAttachments.classify("a.csv", "text/csv", "a,b".toByteArray()))
    }

    @Test fun uploadParamsMatchTheGatewayContract() {
        val p = GroupAttachments.uploadParams("room1", "u1", "n.png", "image/png", png)
        assertEquals("room1", p.getString("room_id")); assertEquals("u1", p.getString("upload_id"))
        assertEquals("image", p.getString("kind")); assertEquals("image/png", p.getString("mime"))
        assertEquals(java.util.Base64.getEncoder().encodeToString(png), p.getString("data_base64"))
        val d = GroupAttachments.downloadParams("room1", "ev", "att")
        assertEquals("ev", d.getString("event_id")); assertEquals("att", d.getString("attachment_id"))
    }

    @Test fun uploadsInOrderThenSendsOneMessageWithManifests() = runBlocking {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var n = 0
        val fake: suspend (String, JSONObject) -> JSONObject = { m, p ->
            calls += m to p
            if (m == "groups.attachment.upload") JSONObject().put("attachment_id", "att_${++n}").put("kind", p.getString("kind"))
                .put("name", p.getString("name")).put("mime", p.getString("mime")).put("size", 10).put("storage_path", "x")
            else JSONObject().put("event", JSONObject())
        }
        var ids = 0
        GroupAttachments.sendWithAttachments(fake, "r", "hi", "t-1", "ev-1",
            listOf(Triple("a.png", "image/png", png), Triple("b.pdf", "application/pdf", pdf))) { "up${++ids}" }
        assertEquals(listOf("groups.attachment.upload", "groups.attachment.upload", "groups.send"), calls.map { it.first })
        val payload = calls.last().second.getJSONObject("payload")
        assertEquals("hi", payload.getString("text"))
        val arr = payload.getJSONArray("attachments")
        assertEquals(2, arr.length()); assertEquals("att_1", arr.getJSONObject(0).getString("attachment_id"))
        assertFalse("receipt storage metadata must not be sent", arr.getJSONObject(0).has("storage_path"))
        assertEquals("ev-1", calls.last().second.getString("event_id"))
    }

    @Test fun failedUploadSendsNothing() {
        val calls = mutableListOf<String>()
        val fake: suspend (String, JSONObject) -> JSONObject = { m, _ -> calls += m; if (calls.size == 2) error("boom") else JSONObject().put("attachment_id", "a") }
        val r = runCatching { runBlocking {
            GroupAttachments.sendWithAttachments(fake, "r", "", "t", "e",
                listOf(Triple("a.png", "image/png", png), Triple("b.png", "image/png", png))) { "u" } } }
        assertTrue(r.isFailure); assertFalse(calls.contains("groups.send"))
    }

    @Test fun limitsAreEnforcedBeforeAnyRequest() {
        var called = false
        val fake: suspend (String, JSONObject) -> JSONObject = { _, _ -> called = true; JSONObject() }
        val nine = (1..9).map { Triple("f$it", "text/plain", ByteArray(1)) }
        assertTrue(runCatching { runBlocking { GroupAttachments.sendWithAttachments(fake, "r", "", "t", "e", nine) { "u" } } }.isFailure)
        assertFalse(called)
    }

    @Test fun eventAttachmentsParseAndSkipIdless() {
        val payload = JSONObject("""{"attachments":[{"attachment_id":"a1","kind":"image","name":"p.png","mime":"image/png"},{"name":"x"}]}""")
        val list = GroupAttachments.fromEvent(payload)
        assertEquals(1, list.size); assertEquals("image", list[0].kind)
    }

    @Test fun voiceMarkerOnlyOnVoiceSends() {
        assertTrue(GroupAttachments.sendPayload("hi", "t", emptyList(), voice = true).optBoolean("voice"))
        assertFalse(GroupAttachments.sendPayload("hi", "t", emptyList()).has("voice"))
    }
}
