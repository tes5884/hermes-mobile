package com.nousresearch.hermesmobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Composer rows in clear text, by composer key. [write] (null: remove) is on disk when it returns, or throws. */
interface ComposerRows {
    fun read(key: String): String?
    fun write(key: String, row: String?)
    fun keys(): Set<String>
}

/** The rows on the phone: their own encrypted preferences file, every write a synchronous commit(). */
class PrefsComposerRows(context: Context) : ComposerRows {
    private val prefs = context.getSharedPreferences("hermes_mobile_chat", Context.MODE_PRIVATE)

    override fun read(key: String): String? =
        prefs.getString(key, null)?.let { runCatching { LocalCipher.decrypt(it) }.getOrNull() }

    override fun write(key: String, row: String?) {
        val edit = prefs.edit()
        if (row == null) edit.remove(key) else edit.putString(key, LocalCipher.encrypt(row))
        check(edit.commit()) { "Could not save the draft" }
    }

    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Encrypted per-conversation drafts (text + attachments). Messages you sent are not here: they live in the [Outbox]
 * from the moment you press send, so nothing written to a composer can overwrite them. [State.queue] is only read, from
 * composers saved by older versions, to move it into the outbox.
 *
 * Every write is durable or throws (a failed write stops and says so). A queue an older version left in a composer is
 * carried over by every draft save, untouched, until [dropLegacyQueue], which the caller runs only once the outbox has
 * that queue on disk (Shomer t_737920a6): no draft save, close or clear can drop it.
 */
class ChatComposerStore(private val rows: ComposerRows) : ComposerSaves {
    constructor(context: Context) : this(PrefsComposerRows(context))

    data class State(
        val draft: String = "",
        val attachments: List<ComposerAttachment> = emptyList(),
        /** Legacy: a queue saved by an older version (moved into the outbox when the chat opens). */
        val queue: List<QueuedPrompt> = emptyList()
    )

    @Synchronized
    override fun load(key: String): State {
        val row = rows.read(key) ?: return State()
        return runCatching {
            val root = JSONObject(row)
            State(
                draft = root.optString("draft"),
                attachments = attachmentsFromJson(root.optJSONArray("attachments")),
                queue = buildList {
                    val arr = root.optJSONArray("queue") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { item ->
                            add(
                                QueuedPrompt(
                                    id = item.optString("id"),
                                    text = item.optString("text"),
                                    attachments = attachmentsFromJson(item.optJSONArray("attachments")),
                                    createdAt = item.optLong("created_at", 0L),
                                    hold = item.optString("hold").takeIf { it in QueuedPrompt.HOLDS }
                                )
                            )
                        }
                    }
                }
            )
        }.getOrDefault(State())
    }

    /** Saves the draft and its attachments (never a new queue), carrying over any legacy queue. Throws when refused. */
    @Synchronized
    override fun save(key: String, state: State) {
        rows.write(key, row(state.draft, state.attachments, legacyQueue(key)))
    }

    /** The outbox has the old queue on disk: the composer lets go of it, its draft kept. Throws when refused. */
    @Synchronized
    override fun dropLegacyQueue(key: String) {
        legacyQueue(key) ?: return
        val saved = load(key)
        rows.write(key, row(saved.draft, saved.attachments, null))
    }

    /** The row to store (null: nothing to keep, the row is removed). */
    private fun row(draft: String, attachments: List<ComposerAttachment>, queue: JSONArray?): String? {
        if (draft.isBlank() && attachments.isEmpty() && queue == null) return null
        return JSONObject()
            .put("draft", draft)
            .put("attachments", attachmentsToJson(attachments))
            .apply { queue?.let { put("queue", it) } }
            .toString()
    }

    /** The raw queue an older version saved under [key], if any. */
    private fun legacyQueue(key: String): JSONArray? =
        rows.read(key)?.let { runCatching { JSONObject(it).optJSONArray("queue") }.getOrNull() }?.takeIf { it.length() > 0 }

    fun referencedAttachmentUris(): Set<String> = rows.keys().flatMap { key ->
        val state = load(key)
        state.attachments.map { it.uri } + state.queue.flatMap { it.attachments.map { file -> file.uri } }
    }.toSet()

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
