package com.nousresearch.hermesmobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encrypted per-conversation drafts (text + attachments). Messages you sent are not here: they live in the [Outbox]
 * from the moment you press send, so nothing written to a composer can overwrite them. [State.queue] is only read, from
 * composers saved by older versions, to move it into the outbox once.
 */
class ChatComposerStore(context: Context) : ComposerSaves {
    private val prefs = context.getSharedPreferences("hermes_mobile_chat", Context.MODE_PRIVATE)

    data class State(
        val draft: String = "",
        val attachments: List<ComposerAttachment> = emptyList(),
        /** Legacy: a queue saved by an older version (moved into the outbox when the chat opens). */
        val queue: List<QueuedPrompt> = emptyList()
    )

    override fun load(key: String): State {
        val encoded = prefs.getString(key, null) ?: return State()
        return runCatching {
            val root = JSONObject(LocalCipher.decrypt(encoded))
            State(
                draft = root.optString("draft"),
                attachments = attachmentsFromJson(root.optJSONArray("attachments")),
                queue = buildList {
                    val arr = root.optJSONArray("queue") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { row ->
                            add(
                                QueuedPrompt(
                                    id = row.optString("id"),
                                    text = row.optString("text"),
                                    attachments = attachmentsFromJson(row.optJSONArray("attachments")),
                                    createdAt = row.optLong("created_at", 0L),
                                    hold = row.optString("hold").takeIf { it in QueuedPrompt.HOLDS }
                                )
                            )
                        }
                    }
                }
            )
        }.getOrDefault(State())
    }

    /** Saves the draft and its attachments (never a queue: sent messages are the outbox's). */
    override fun save(key: String, state: State) {
        if (state.draft.isBlank() && state.attachments.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val root = JSONObject()
            .put("draft", state.draft)
            .put("attachments", attachmentsToJson(state.attachments))
        prefs.edit().putString(key, LocalCipher.encrypt(root.toString())).apply()
    }

    fun referencedAttachmentUris(): Set<String> = prefs.all.keys.flatMap { key ->
        val state = load(key)
        state.attachments.map { it.uri } + state.queue.flatMap { it.attachments.map { file -> file.uri } }
    }.toSet()

    fun remove(key: String) = prefs.edit().remove(key).apply()

    private fun attachmentsToJson(items: List<ComposerAttachment>): JSONArray = JSONArray(items.map {
        JSONObject()
            .put("id", it.id)
            .put("uri", it.uri)
            .put("name", it.name)
            .put("mime", it.mimeType)
            .put("size", it.size)
    })

    private fun attachmentsFromJson(array: JSONArray?): List<ComposerAttachment> = buildList {
        val arr = array ?: return@buildList
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
            add(
                ComposerAttachment(
                    id = it.optString("id"),
                    uri = it.optString("uri"),
                    name = it.optString("name"),
                    mimeType = it.optString("mime", "application/octet-stream"),
                    size = it.optLong("size", 0L)
                )
            )
        }
    }
}
