package com.nousresearch.hermesmobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How the open room's data relates to the server right now. Drives the one status line; never blocks the view. */
enum class RoomLink { CONNECTING, LIVE, OFFLINE }

/** A room as saved on the phone: meta + the newest events. */
data class CachedRoom(val room: GroupRoom, val events: List<GroupEvent>)

/** The rooms list as saved on the phone (rows + their last-message previews). */
data class CachedRoomList(val rooms: List<GroupRoom>, val previews: Map<String, RoomPreview>)

/**
 * Offline cache for hosted rooms: the last [limit] events per room plus the rooms list, as small JSON files in the
 * app's private storage (one folder per gateway connection). Reading and writing never throws: a missing or
 * corrupt file is just "no cache".
 */
class RoomCache(baseDir: File, connectionId: String, private val limit: Int = DEFAULT_LIMIT) {
    private val dir = File(baseDir, safe(connectionId))

    fun loadRoom(roomId: String): CachedRoom? = runCatching {
        val o = JSONObject(File(dir, "room_${safe(roomId)}.json").readText())
        CachedRoom(
            GroupRoom.fromJson(o.getJSONObject("room")),
            (o.optJSONArray("events") ?: JSONArray()).let { a -> (0 until a.length()).map { GroupEvent.fromJson(a.getJSONObject(it)) } }
                .distinctBy { it.seq }.sortedBy { it.seq }
        )
    }.getOrNull()

    fun saveRoom(room: GroupRoom, events: List<GroupEvent>) {
        val tail = merge(emptyList(), events, limit)
        write("room_${safe(room.roomId)}.json", JSONObject()
            .put("room", roomToJson(room))
            .put("events", JSONArray().also { a -> tail.forEach { a.put(eventToJson(it)) } }))
    }

    fun loadList(): CachedRoomList? = runCatching {
        val o = JSONObject(File(dir, "list.json").readText())
        val rooms = o.getJSONArray("rooms").let { a -> (0 until a.length()).map { GroupRoom.fromJson(a.getJSONObject(it)) } }
        val pv = o.optJSONObject("previews") ?: JSONObject()
        val previews = pv.keys().asSequence().associateWith { k ->
            val p = pv.getJSONObject(k)
            RoomPreview(p.optLong("seq"), p.optBoolean("from_user"), p.optString("sender"), p.optString("line"), p.optDouble("created_at", 0.0))
        }
        CachedRoomList(rooms, previews)
    }.getOrNull()

    fun saveList(rooms: List<GroupRoom>, previews: Map<String, RoomPreview>) {
        write("list.json", JSONObject()
            .put("rooms", JSONArray().also { a -> rooms.forEach { a.put(roomToJson(it)) } })
            .put("previews", JSONObject().also { o ->
                previews.forEach { (id, p) ->
                    o.put(id, JSONObject().put("seq", p.seq).put("from_user", p.fromUser).put("sender", p.sender)
                        .put("line", p.line).put("created_at", p.createdAt))
                }
            }))
    }

    fun clear() { runCatching { dir.deleteRecursively() } }

    private fun write(name: String, o: JSONObject) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 200

        private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "_" }

        /** Merge by seq: one event per seq (the fresh copy wins), oldest first, keeping the newest [limit]. */
        fun merge(cached: List<GroupEvent>, fresh: List<GroupEvent>, limit: Int = DEFAULT_LIMIT): List<GroupEvent> =
            (cached + fresh).associateBy { it.seq }.values.sortedBy { it.seq }.takeLast(limit)

        fun roomToJson(r: GroupRoom): JSONObject = JSONObject()
            .put("room_id", r.roomId).put("name", r.name)
            .put("members", JSONArray().also { a ->
                r.members.forEach { m ->
                    a.put(JSONObject().put("member_id", m.memberId).put("profile", m.profile).put("handle", m.handle)
                        .put("display_name", m.displayName)
                        .put("target", JSONObject().put("kind", m.targetKind).put("profile", m.targetProfile)
                            .put("peer_id", m.peerId).put("installation_id", m.installationId)
                            .put("capability_digest", m.capabilityDigest)))
                }
            })
            .put("authority_gateway_id", r.authorityGatewayId).put("authority_epoch", r.authorityEpoch)
            .put("revision", r.revision).put("created_at", r.createdAt).put("updated_at", r.updatedAt)
            .put("latest_seq", r.latestSeq)
            .also { o -> r.disbandedAt?.let { o.put("disbanded_at", it) } }

        fun eventToJson(e: GroupEvent): JSONObject = JSONObject()
            .put("room_id", e.roomId).put("seq", e.seq).put("event_id", e.eventId).put("kind", e.kind)
            .put("actor", JSONObject().put("kind", e.actor.kind).put("id", e.actor.id).put("profile", e.actor.profile)
                .put("display_name", e.actor.displayName).put("connection_id", e.actor.connectionId))
            .put("payload", e.payload).put("created_at", e.createdAt)
            .also { o -> e.authorityEpoch?.let { o.put("authority_epoch", it) } }
    }
}
