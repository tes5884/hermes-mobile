package com.nousresearch.hermesmobile.data

/**
 * The pure rules behind a channel (Nova's mock 5): a Hermes Group Chat room where several bots answer in parallel.
 * The header names the members, each bot reply carries avatar + name + time (dropped for a quick follow-up by the
 * same bot), waiting approval cards sit at the live bottom (with a banner + jump while scrolled away), answered ones where
 * they were answered, and one "<Bot> is typing" line per
 * bot that is answering right now stacks at the bottom.
 */
object ChannelView {
    /** A bot's next reply within this long (and nothing in between) doesn't repeat its avatar and name. */
    const val GROUP_GAP_MS = 5 * 60_000L

    /** [ApprovalCardStore] key for channels: their cards are stored per room id under this pseudo-profile. */
    const val STORE_PROFILE = "@channel"

    /** The first [max] member names for the header line, and how many more there are. */
    fun headerNames(members: List<GroupMember>, max: Int = 3): Pair<List<String>, Int> {
        val names = members.map { it.label.ifBlank { it.handle } }.filter { it.isNotBlank() }
        return names.take(max) to (names.size - max).coerceAtLeast(0)
    }

    /**
     * Seqs of bot replies whose avatar + name header is dropped: the row right before is the same bot's reply,
     * both timed, at most [GROUP_GAP_MS] apart. A day divider, the new-messages line, any other event or a
     * routine post in between keeps the header. [rows] are oldest first, as [RoomRows.build] returns them.
     */
    fun hiddenAuthors(rows: List<RoomRow>, gapMs: Long = GROUP_GAP_MS): Set<Long> {
        val out = HashSet<Long>()
        var prev: GroupEvent? = null
        for (row in rows) {
            val e = (row as? RoomRow.Event)?.event
            if (e == null) { prev = null; continue }
            val p = prev
            if (p != null && isBotReply(p) && isBotReply(e) && p.memberId == e.memberId) {
                val a = RoomRows.millis(p)
                val b = RoomRows.millis(e)
                if (a != null && b != null && b - a in 0..gapMs) out.add(e.seq)
            }
            prev = e
        }
        return out
    }

    private fun isBotReply(e: GroupEvent) = e.kind == "message.member" && !RoutinePosts.isScheduled(e)

    /** The id an approval is answered and stored under: Hermes' request id, else the exact attempt. */
    fun approvalKey(a: GroupPendingAction): String = a.requestId.ifBlank { "${a.taskId}#${a.executionGeneration}" }

    /**
     * The card for a room approval. Hosted rooms only ever offer once / deny (the server filters the choices), so
     * Always is never shown and the card reads "This kind always asks".
     */
    fun card(a: GroupPendingAction, firstSeenMs: Long): ApprovalCards.Card =
        ApprovalCards.fromRequest(approvalKey(a), a.raw.optJSONObject("approval") ?: a.raw, firstSeenMs)
            .let { if (it.command.isBlank() && it.description.isBlank()) it.copy(command = a.command, description = a.description) else it }
            .copy(allowAlways = false)

    sealed class Item(val key: String) {
        class Pending(val action: GroupPendingAction, val card: ApprovalCards.Card) : Item(card.requestId)
        class Answered(val card: ApprovalCards.Card) : Item(card.requestId)
    }

    data class Placement(val under: Map<Long, List<Item>>, val bottom: List<Item>)

    /**
     * Where each approval card goes. A waiting one always sits at the live bottom of the transcript, just above the
     * typing lines, never under the asking bot's last reply: in a busy channel that reply can be a hundred messages
     * up, and a card there can expire unseen while he reads the latest ones (Shomer, t_86879164). The card names the
     * bot, so it doesn't need the reply above it. One answered from this phone is placed by time (after the last row
     * at or before it arrived) and is not shown once it is older than the loaded history. A waiting request this phone already
     * answered is not shown twice: the answered card stands until the server drops the request. That match needs
     * Hermes' request id; a request without one is always shown, so a second ask in the same attempt can't hide
     * behind the first one's answer.
     * [events] are the visible events, oldest first.
     */
    fun place(
        events: List<GroupEvent>,
        pending: List<GroupPendingAction>,
        answered: List<ApprovalCards.Card>,
        firstSeenMs: (GroupPendingAction) -> Long
    ): Placement {
        val under = LinkedHashMap<Long, MutableList<Item>>()
        val bottom = ArrayList<Item>()
        val answeredIds = answered.map { it.requestId }.toSet()
        val anchors = ApprovalCards.anchors(answered, events.map { RoomRows.millis(it) ?: 0L })
        answered.sortedBy { it.createdAtMs }.forEach { card ->
            val at = anchors[card.requestId] ?: -1
            if (at >= 0) under.getOrPut(events[at].seq) { ArrayList() }.add(Item.Answered(card))
        }
        pending.filter { it.kind == "approval" && (it.requestId.isBlank() || approvalKey(it) !in answeredIds) }.forEach { action ->
            bottom.add(Item.Pending(action, card(action, firstSeenMs(action))))
        }
        return Placement(under, bottom)
    }

    /** One entry of the channel's reverse-layout transcript; index 0 is the newest (the live bottom). */
    sealed class Entry(val key: String) {
        object TypingLines : Entry("typing")
        class Note(val id: String) : Entry("p:$id")
        class Approval(val item: Item) : Entry("a:" + item.key)
        class Row(val row: RoomRow) : Entry(row.key)
    }

    /**
     * Exactly what the channel's LazyColumn draws, in its order (newest first): the typing lines, his pending voice
     * notes (newest lowest), the waiting approvals, then the transcript rows newest first. The screen renders this
     * list as is, so a test on it is a test on what is on screen. Answered cards ride inside their row
     * ([Placement.under]). [noteIds] and [rows] are oldest first, as [RoomRows.build] returns rows.
     */
    fun liveEntries(typing: Boolean, noteIds: List<String>, placement: Placement, rows: List<RoomRow>): List<Entry> {
        val out = ArrayList<Entry>(rows.size + noteIds.size + placement.bottom.size + 1)
        if (typing) out.add(Entry.TypingLines)
        noteIds.asReversed().forEach { out.add(Entry.Note(it)) }
        placement.bottom.asReversed().forEach { out.add(Entry.Approval(it)) }
        rows.asReversed().forEach { out.add(Entry.Row(it)) }
        return out
    }

    /** The "needs your OK" banner over the transcript: who is waiting, how many, and the entry to jump to. */
    data class Banner(val memberIds: List<String>, val count: Int, val jumpIndex: Int)

    /** A laid-out list item: its key, start offset and size in px (LazyListItemInfo, minus Compose). */
    data class Laid(val key: Any, val offset: Int, val size: Int)

    /**
     * The keys that count as on screen: at least half of the item, or at least [minPx] of it, inside the viewport.
     * A card with only a sliver peeking in at the edge isn't one he can read or tap, so its banner stays.
     */
    fun onScreenKeys(items: List<Laid>, viewportStart: Int, viewportEnd: Int, minPx: Int): Set<String> =
        items.filter { item ->
            val shown = minOf(item.offset + item.size, viewportEnd) - maxOf(item.offset, viewportStart)
            shown > 0 && (shown * 2 >= item.size || shown >= minPx)
        }.mapNotNull { it.key as? String }.toSet()

    /**
     * A banner while any approval is waiting and none of the waiting cards is on screen (he scrolled up, or the room
     * opened at the unread divider or a tapped post). It stays until a waiting card is on screen or the request is
     * answered or gone; its jump goes to the newest waiting card. [visibleKeys] are the entry keys on screen.
     */
    fun approvalBanner(entries: List<Entry>, visibleKeys: Set<String>): Banner? {
        val waiting = entries.withIndex().filter { (_, e) -> e is Entry.Approval && e.item is Item.Pending }
        if (waiting.isEmpty() || waiting.any { it.value.key in visibleKeys }) return null
        val members = waiting.map { ((it.value as Entry.Approval).item as Item.Pending).action.memberId }.distinct()
        return Banner(members, waiting.size, waiting.first().index)
    }

    data class Typing(val memberId: String, val tool: String)

    /** One line per bot answering right now (queued and stopping bots aren't typing), in the server's order. */
    fun typing(activity: List<GroupMemberActivity>): List<Typing> =
        activity.filter { it.status == "running" }
            .groupBy { it.memberId }
            .map { (id, rows) -> Typing(id, rows.firstOrNull { it.tool.isNotBlank() }?.tool.orEmpty()) }

    /** Spans of "@handle" for the channel's members in a message, for the blue mentions in his bubbles. */
    fun mentionRanges(text: String, handles: Collection<String>): List<IntRange> {
        if (handles.isEmpty() || '@' !in text) return emptyList()
        val known = handles.filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
        return Regex("(?<![\\w@])@([A-Za-z0-9_.-]+)").findAll(text).mapNotNull { m ->
            val handle = m.groupValues[1].trimEnd('.', '-')
            if (handle.lowercase() in known) m.range.first..(m.range.first + handle.length) else null
        }.toList()
    }
}
