package com.nousresearch.hermesmobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** One file in a Group Chat message: the manifest `groups.send` accepts and events carry back. */
data class GroupAttachment(
    val attachmentId: String,
    val kind: String,
    val name: String,
    val mime: String,
    val size: Long = 0L
) {
    fun toManifest(): JSONObject = JSONObject()
        .put("attachment_id", attachmentId).put("kind", kind).put("name", name).put("mime", mime).put("size", size)

    companion object {
        fun fromJson(o: JSONObject) = GroupAttachment(
            attachmentId = o.optString("attachment_id"),
            kind = o.optString("kind", "file"),
            name = o.optString("name").ifBlank { "attachment" },
            mime = o.optString("mime", "application/octet-stream"),
            size = o.optLong("size", 0L)
        )
    }
}

/** A downloaded room file. */
class GroupAttachmentFile(val name: String, val mime: String, val bytes: ByteArray)

/** Request building and the upload-then-send sequence for Group Chat attachments (gateway limits mirrored). */
object GroupAttachments {
    const val MAX_PER_MESSAGE = 8
    const val MAX_FILE_BYTES = 15_000_000L
    const val MAX_MESSAGE_BYTES = 25_000_000L

    /** The gateway checks image/PDF bytes against the declared MIME, so declare what the bytes are. */
    fun sniffImageMime(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && String(b, 0, 6, Charsets.ISO_8859_1).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        else -> null
    }

    /** kind + mime to declare for these bytes; an image the gateway can't verify (HEIC...) goes up as a plain file. */
    fun classify(name: String, declaredMime: String, bytes: ByteArray): Pair<String, String> {
        val declared = declaredMime.trim().lowercase().ifBlank { "application/octet-stream" }
        if (declared.startsWith("image/")) {
            return sniffImageMime(bytes)?.let { "image" to it } ?: ("file" to "application/octet-stream")
        }
        if (declared == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
            return if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF") "pdf" to "application/pdf"
            else "file" to "application/octet-stream"
        }
        if (declared.startsWith("text/") && bytes.any { it == 0.toByte() }) return "file" to "application/octet-stream"
        return "file" to declared
    }

    fun uploadParams(roomId: String, uploadId: String, name: String, declaredMime: String, bytes: ByteArray): JSONObject {
        val (kind, mime) = classify(name, declaredMime, bytes)
        return JSONObject()
            .put("room_id", roomId).put("upload_id", uploadId).put("kind", kind).put("name", name).put("mime", mime)
            .put("data_base64", Base64.getEncoder().encodeToString(bytes))
    }

    fun downloadParams(roomId: String, eventId: String, attachmentId: String): JSONObject =
        JSONObject().put("room_id", roomId).put("event_id", eventId).put("attachment_id", attachmentId)

    fun sendPayload(text: String, threadId: String, attachments: List<GroupAttachment>, voice: Boolean = false): JSONObject {
        val payload = JSONObject().put("text", text).put("thread_id", threadId)
        if (voice) payload.put("voice", true)
        if (attachments.isNotEmpty()) payload.put("attachments", JSONArray().also { a -> attachments.forEach { a.put(it.toManifest()) } })
        return payload
    }

    /** Attachments carried by a room event, tagged as readable only when the event id is known. */
    fun fromEvent(payload: JSONObject): List<GroupAttachment> {
        val arr = payload.optJSONArray("attachments") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map(GroupAttachment::fromJson).filter { it.attachmentId.isNotBlank() }
    }

    fun parseDownload(o: JSONObject) = GroupAttachmentFile(
        name = o.optString("name").ifBlank { "attachment" },
        mime = o.optString("mime", "application/octet-stream"),
        bytes = Base64.getDecoder().decode(o.optString("data_base64"))
    )

    /**
     * Upload each file (in order), then send one message with the manifests. A failed upload throws before anything is
     * sent, so no half-message reaches the room; the caller keeps the draft and files to retry.
     */
    suspend fun sendWithAttachments(
        request: suspend (method: String, params: JSONObject) -> JSONObject,
        roomId: String,
        text: String,
        threadId: String,
        eventId: String,
        files: List<Triple<String, String, ByteArray>>,
        newUploadId: () -> String,
        voice: Boolean = false
    ): JSONObject {
        require(files.size <= MAX_PER_MESSAGE) { "At most $MAX_PER_MESSAGE files per message" }
        require(files.sumOf { it.third.size.toLong() } <= MAX_MESSAGE_BYTES) { "Files total more than 25 MB" }
        files.forEach { require(it.third.size <= MAX_FILE_BYTES) { "${it.first} is larger than 15 MB" } }
        val manifests = files.map { (name, mime, bytes) ->
            GroupAttachment.fromJson(request("groups.attachment.upload", uploadParams(roomId, newUploadId(), name, mime, bytes)))
        }
        return request(
            "groups.send",
            JSONObject().put("room_id", roomId).put("event_id", eventId).put("payload", sendPayload(text, threadId, manifests, voice))
        )
    }
}
