package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.*
import com.nousresearch.hermesmobile.ui.components.DashedDivider
import com.nousresearch.hermesmobile.ui.components.HermesTextField
import com.nousresearch.hermesmobile.ui.components.ProfileAvatar
import com.nousresearch.hermesmobile.ui.components.forPoster
import com.nousresearch.hermesmobile.ui.theme.hermes
import kotlinx.coroutines.launch

/**
 * An open channel (Nova's mock 5): a Hermes Group Chat room where several bots answer in parallel. Header = back,
 * stacked avatars, the name and who's in it (tap for members, mention, rename, delete). Bot replies have no bubble
 * (avatar, name, time, then the words), his messages sit in soft bubbles with the mentions in colour, waiting
 * approval cards sit at the live bottom (with a banner + Show while one is off screen), answered ones where they were
 * answered, and one "<Bot> is typing" line per answering bot stacks at the
 * bottom. The composer is the bot chat's: +, a pill field, the 60dp hold-to-talk mic.
 */
@Composable
internal fun ChannelRoomView(
    active: ActiveGroupRoom,
    profiles: List<HermesProfile>,
    actionBusy: Boolean,
    avatarAssets: Map<String, String>,
    onEnsureAvatar: (HermesProfile) -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onReplyThread: (String?) -> Unit,
    onRename: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: (GroupPendingAction) -> Unit,
    onApprove: (GroupPendingAction, String) -> Unit,
    onDisband: () -> Unit
) {
    var input by remember(active.room.roomId) { mutableStateOf("") }
    var rename by remember { mutableStateOf(false) }
    var disband by remember { mutableStateOf(false) }
    // Reverse-layout list like the 1:1 transcript: index 0 is the newest row, so the room opens at the
    // latest message, stays pinned there as events arrive, and never moves a reader who scrolled up.
    val listState = remember(active.room.roomId) { LazyListState() }
    val visibleEvents = remember(active.events) { active.events.filter { shouldRenderRoomEvent(it) } }
    // Phone-side "where I left off": the highest seq shown per room. The divider is fixed when the room opens and
    // stays until he leaves it; the saved position only moves once the initial scroll is done.
    val readPrefs = LocalContext.current.getSharedPreferences("hermes_room_read", android.content.Context.MODE_PRIVATE)
    val roomKey = active.room.roomId
    val initialSaved = remember(roomKey) { RoomRead.load(readPrefs, roomKey) }
    var dividerSeq by remember(roomKey) { mutableStateOf<Long?>(null) }
    var anchored by remember(roomKey) { mutableStateOf(false) }
    var ready by remember(roomKey) { mutableStateOf(false) }
    var seenSeq by remember(roomKey) { mutableLongStateOf(initialSaved ?: 0L) }
    val rows = remember(visibleEvents, dividerSeq) { RoomRows.build(visibleEvents, newMessagesBeforeSeq = dividerSeq) }
    val hiddenAuthors = remember(rows) { ChannelView.hiddenAuthors(rows) }
    // Approvals: waiting ones at the live bottom, answered ones by time (ChannelView.place).
    val placement = remember(visibleEvents, active.driver.pendingActions, active.approvalCards) {
        ChannelView.place(visibleEvents, active.driver.pendingActions, active.approvalCards) { System.currentTimeMillis() }
    }
    val typing = remember(active.driver.memberActivity) { ChannelView.typing(active.driver.memberActivity) }
    val attachActions = LocalGroupAttachmentActions.current
    val pendingNotes = attachActions.pendingVoice.filter { it.roomId == active.room.roomId }
    val notesById = pendingNotes.associateBy { it.id }
    val noteIds = pendingNotes.map { it.id }
    // Exactly what the list draws, newest first; every scroll target below is an index into this.
    val entries = remember(typing.isNotEmpty(), noteIds, placement, rows) { ChannelView.liveEntries(typing.isNotEmpty(), noteIds, placement, rows) }
    fun indexOfRow(match: (RoomRow) -> Boolean): Int = entries.indexOfFirst { it is ChannelView.Entry.Row && match(it.row) }
    LaunchedEffect(roomKey, visibleEvents.isNotEmpty()) {
        if (visibleEvents.isNotEmpty() && !anchored) {
            anchored = true
            dividerSeq = RoomRead.firstUnreadSeq(visibleEvents, initialSaved)
            if (dividerSeq == null) ready = true
        }
    }
    val density = LocalDensity.current
    LaunchedEffect(dividerSeq) {
        val target = dividerSeq ?: return@LaunchedEffect
        val idx = indexOfRow { it is RoomRow.NewMessages }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            // The divider now sits at the bottom edge; move the list back toward the newest so it lands near the top.
            val h = listState.layoutInfo.viewportSize.height
            if (h > 0) listState.scrollBy(-(h - with(density) { 96.dp.toPx() }).coerceAtLeast(0f))
        }
        ready = true
    }
    // A tapped routine notice: land on that post (after the unread-divider scroll), once.
    val pushAnchor = PushAnchor.pending(room = true)
    LaunchedEffect(roomKey, ready, pushAnchor, entries.size) {
        if (!ready || pushAnchor == null) return@LaunchedEffect
        val post = RoutinePosts.roomTarget(visibleEvents, pushAnchor.text) ?: return@LaunchedEffect
        val idx = indexOfRow { it is RoomRow.Event && it.event.seq == post.seq }
        if (idx >= 0) { listState.scrollToItem(idx); PushAnchor.clear() }
    }
    LaunchedEffect(roomKey, ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { k -> k.startsWith("e:") }?.drop(2)?.toLongOrNull() }.maxOrNull() ?: 0L
        }.collect { top ->
            if (top > seenSeq) { seenSeq = top; RoomRead.save(readPrefs, roomKey, top) }
        }
    }
    LaunchedEffect(roomKey, ready, visibleEvents) {
        if (!ready) return@LaunchedEffect
        val own = RoomRead.newestOwnSeq(visibleEvents)
        if (own > seenSeq) { seenSeq = own; RoomRead.save(readPrefs, roomKey, own) }
    }
    // Sending (text or voice) snaps to the newest row and keeps it there for a moment while the message lays out.
    var pinTick by remember(roomKey) { mutableIntStateOf(0) }
    var pinActive by remember(roomKey) { mutableStateOf(false) }
    LaunchedEffect(pinTick) {
        if (pinTick > 0) { pinActive = true; listState.scrollToItem(0); kotlinx.coroutines.delay(2500); pinActive = false }
    }
    LaunchedEffect(entries.size) { if (pinActive) listState.scrollToItem(0) }
    val membersById = remember(active.room.members) { active.room.members.associateBy { it.memberId } }
    val profilesByName = remember(profiles) { profiles.associateBy { it.name } }
    active.room.members.forEach { m -> key(m.memberId) { profilesByName[m.profile]?.let { p -> LaunchedEffect(p.name, p.hasAvatar) { onEnsureAvatar(p) } } } }
    val handles = remember(active.room.members) { active.room.members.map { it.handle } }
    fun nameOf(memberId: String): String {
        val m = membersById[memberId] ?: return memberId
        return profilesByName[m.profile]?.label?.takeIf { it.isNotBlank() } ?: m.label
    }

    val otherActions = remember(active.driver.pendingActions) { active.driver.pendingActions.filter { it.kind != "approval" } }
    val answer: (GroupPendingAction, String) -> Unit = { action, choice -> if (!actionBusy) onApprove(action, choice) }

    // An open room is a view inside Bots: back closes it, it doesn't leave the page.
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        ChannelHeader(
            room = active.room, profilesByName = profilesByName, avatarAssets = avatarAssets, blocked = active.driver.blocked,
            working = active.driver.working, actionBusy = actionBusy, onBack = onBack, onStop = onStop,
            onMention = { handle -> input = appendMention(input, handle) }, onRename = { rename = true }, onDisband = { disband = true }
        )
        DashedDivider(color = MaterialTheme.hermes.line)
        if (active.link != RoomLink.LIVE) {
            Text(
                stringResource(if (active.link == RoomLink.OFFLINE) R.string.room_link_offline else R.string.room_link_connecting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        // A stalled room's Retry (and anything that isn't an approval) keeps its card above the transcript.
        if (otherActions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                otherActions.forEach { action -> PendingRoomActionCard(action, membersById[action.memberId], actionBusy, onRetry, onApprove) }
            }
        }

        val empty = visibleEvents.isEmpty() && pendingNotes.isEmpty() && typing.isEmpty() && placement.bottom.isEmpty()
        if (empty) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ui_ea79140946), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val awayFromBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
            // While an approval waits and its card isn't on screen, a banner over the transcript says who is
            // waiting and jumps to the card (Shomer, t_86879164): it can't expire unseen while he reads history.
            val minOnScreenPx = with(density) { 96.dp.roundToPx() }
            val banner by remember(listState, entries) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val onScreen = ChannelView.onScreenKeys(
                        info.visibleItemsInfo.map { ChannelView.Laid(it.key, it.offset, it.size) },
                        info.viewportStartOffset, info.viewportEndOffset, minOnScreenPx
                    )
                    ChannelView.approvalBanner(entries, onScreen)
                }
            }
            val listScope = rememberCoroutineScope()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = if (banner != null) 64.dp else 8.dp, bottom = if (awayFromBottom) 52.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Reversed list (ChannelView.liveEntries): index 0 is the bottom. Typing lines sit lowest, then
                    // his pending notes, then every waiting approval, then the transcript.
                    items(entries, key = { it.key }) { entry ->
                        when (entry) {
                            is ChannelView.Entry.TypingLines -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                typing.forEach { t ->
                                    val m = membersById[t.memberId]
                                    TypingLine(m, m?.let { profilesByName[it.profile] }, avatarAssets, nameOf(t.memberId), t.tool)
                                }
                            }
                            is ChannelView.Entry.Note -> notesById[entry.id]?.let { note ->
                                PendingVoiceBubble(note, { attachActions.onVoiceRetry(note.id) }, { attachActions.onVoiceAudioOnly(note.id) }, { attachActions.onVoiceDiscard(note.id) })
                            }
                            is ChannelView.Entry.Approval -> ChannelApproval(entry.item, nameOf(memberIdOf(entry.item)), answer)
                            is ChannelView.Entry.Row -> when (val row = entry.row) {
                                is RoomRow.Day -> DaySeparatorRow(dayLabel(row.millis))
                                is RoomRow.NewMessages -> NewMessagesRow()
                                is RoomRow.Event -> {
                                    val event = row.event
                                    val member = membersById[event.memberId]
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ChannelEventRow(
                                            event = event,
                                            member = member,
                                            profile = member?.let { profilesByName[it.profile] },
                                            avatarAssets = avatarAssets,
                                            name = nameOf(event.memberId),
                                            showAuthor = event.seq !in hiddenAuthors,
                                            handles = handles,
                                            onReplyThread = { event.threadId.takeIf(String::isNotBlank)?.let(onReplyThread) },
                                            onReplyTo = { handle -> input = appendMention(input, handle) }
                                        )
                                        placement.under[event.seq]?.forEach { item -> ChannelApproval(item, nameOf(memberIdOf(item, event.memberId)), answer) }
                                    }
                                }
                            }
                        }
                    }
                }
                banner?.let { b ->
                    ApprovalWaitingBanner(
                        text = if (b.memberIds.size == 1 && b.count == 1) stringResource(R.string.approval_needs_ok, nameOf(b.memberIds.single()))
                            else pluralStringResource(R.plurals.channel_approvals_waiting, b.count, b.count),
                        onShow = { listScope.launch { listState.animateScrollToItem(b.jumpIndex) } },
                        modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                if (awayFromBottom) {
                    Surface(
                        onClick = { listScope.launch { listState.animateScrollToItem(0) } },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 4.dp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    ) {
                        Text(
                            RoomRead.chipLabel(RoomRead.unreadCount(visibleEvents, seenSeq)),
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        active.replyThreadId?.let { thread ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Forum, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.i18n_grp_replying, thread), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onReplyThread(null) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, stringResource(R.string.i18n_grp_cancel_thread), Modifier.size(16.dp)) }
            }
        }
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> if (uris.isNotEmpty()) attachActions.onAttach(uris) }
        val takePhoto = rememberTakePhoto { attachActions.onAttach(listOf(it)) }
        val live = active.link == RoomLink.LIVE
        ChannelComposer(
            roomKey = active.room.roomId,
            draft = input,
            onDraftChange = { input = it },
            attachments = active.composerAttachments,
            onRemoveAttachment = attachActions.onRemove,
            enabled = live && !actionBusy,
            micEnabled = live,
            onAttach = { picker.launch(arrayOf("*/*")) },
            onTakePhoto = takePhoto,
            onSend = { val value = input.trim(); input = ""; onSend(value); pinTick++ },
            onSendVoice = { file, ms -> attachActions.onSendVoice(file, ms); pinTick++ }
        )
    }

    if (rename) {
        var value by remember { mutableStateOf(active.room.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.ui_14fbfb5329)) },
            text = { HermesTextField(value, { value = it }, stringResource(R.string.i18n_name)) },
            confirmButton = { TextButton(enabled = value.trim().isNotBlank() && !actionBusy, onClick = { rename = false; onRename(value) }) { Text(stringResource(R.string.ui_3064d79a29)) } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.ui_19766ed6cc)) } }
        )
    }
    if (disband) {
        AlertDialog(
            onDismissRequest = { disband = false },
            title = { Text(stringResource(R.string.i18n_grp_disband, active.room.name)) },
            text = { Text(stringResource(R.string.ui_71f2b5e341)) },
            confirmButton = { TextButton(enabled = !actionBusy, onClick = { disband = false; onDisband() }) { Text(stringResource(R.string.ui_f2a9142052)) } },
            dismissButton = { TextButton(onClick = { disband = false }) { Text(stringResource(R.string.ui_19766ed6cc)) } }
        )
    }
}

private fun memberIdOf(item: ChannelView.Item, fallback: String = ""): String = when (item) {
    is ChannelView.Item.Pending -> item.action.memberId
    is ChannelView.Item.Answered -> fallback
}

/** An approval card in the channel, indented under the bot's reply (mock 5). Answered ones are the collapsed P2 card. */
@Composable
private fun ChannelApproval(item: ChannelView.Item, botName: String, onAnswer: (GroupPendingAction, String) -> Unit) {
    val indent = Modifier.padding(start = 34.dp)
    when (item) {
        is ChannelView.Item.Pending -> InlineApprovalCard(item.card, botName, onAnswer = { choice -> onAnswer(item.action, choice) }, modifier = indent)
        is ChannelView.Item.Answered -> InlineApprovalCard(item.card, botName, onAnswer = {}, modifier = indent)
    }
}

/**
 * Pinned over the transcript while an approval waits off screen: "Mason needs your OK" (or "2 approvals are
 * waiting") and Show, which scrolls to the waiting card. It goes away once the card is on screen or answered.
 */
@Composable
private fun ApprovalWaitingBanner(text: String, onShow: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onShow,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.channel_approval_show), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/**
 * Back, up to three stacked avatars, the channel's name and "Mason, Nova, Shomer and 2 more". The name block is one
 * tap target (with a chevron) that opens the members (tap to mention), Rename and Delete. Stop shows while bots work.
 */
@Composable
private fun ChannelHeader(
    room: GroupRoom,
    profilesByName: Map<String, HermesProfile>,
    avatarAssets: Map<String, String>,
    blocked: Boolean,
    working: Boolean,
    actionBusy: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onMention: (String) -> Unit,
    onRename: () -> Unit,
    onDisband: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val (names, more) = remember(room.members, profilesByName) {
        ChannelView.headerNames(room.members.map { m -> profilesByName[m.profile]?.label?.takeIf { it.isNotBlank() }?.let { m.copy(displayName = it) } ?: m })
    }
    val membersLine = if (more > 0) pluralStringResource(R.plurals.channel_members_more, more, names.joinToString(", "), more) else names.joinToString(", ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.a21_back)) }
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = stringResource(R.string.channel_members)) { menu = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StackedAvatars(room.members.take(3), profilesByName, avatarAssets)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        membersLine + if (blocked) " · " + stringResource(R.string.i18n_grp_attention) else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                room.members.forEach { m ->
                    val p = profilesByName[m.profile]
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p?.label?.takeIf { it.isNotBlank() } ?: m.label, maxLines = 1)
                                Text(stringResource(R.string.channel_mention, m.handle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        },
                        leadingIcon = { MemberAvatar(m, p, avatarAssets, 28) },
                        onClick = { menu = false; onMention(m.handle) }
                    )
                }
                HorizontalDivider()
                ChannelAdminItems(onRename = { menu = false; onRename() }, onDisband = { menu = false; onDisband() })
            }
        }
        if (working) IconButton(onClick = onStop, enabled = !actionBusy) { Icon(Icons.Default.StopCircle, stringResource(R.string.i18n_grp_stop), tint = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StackedAvatars(members: List<GroupMember>, profilesByName: Map<String, HermesProfile>, avatarAssets: Map<String, String>) {
    val size = 34
    val step = 22
    val ring = MaterialTheme.colorScheme.background
    Box(Modifier.width((size + step * (members.size - 1).coerceAtLeast(0)).dp).height(size.dp)) {
        members.forEachIndexed { i, m ->
            Box(Modifier.offset(x = (i * step).dp).size(size.dp).clip(CircleShape).background(ring).padding(2.dp)) {
                MemberAvatar(m, profilesByName[m.profile], avatarAssets, size - 4, circle = true)
            }
        }
    }
}

/** A member's avatar: the bot's own when it is a profile on this gateway, else its initials. */
@Composable
private fun MemberAvatar(member: GroupMember?, profile: HermesProfile?, avatarAssets: Map<String, String>, size: Int, circle: Boolean = false) {
    if (profile != null) {
        ProfileAvatar(profile, if (circle) Modifier.clip(CircleShape) else Modifier, size = size, avatarDataUrl = avatarAssets[profile.name])
        return
    }
    val label = member?.label.orEmpty().ifBlank { member?.handle.orEmpty() }
    val initials = label.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("").ifBlank { "?" }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.hermes.accentTint).border(1.dp, MaterialTheme.hermes.line, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(initials, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
}

/** "<Bot> is typing •••", or "<Bot> is working · Terminal" when the gateway says which tool it is in. */
@Composable
private fun TypingLine(member: GroupMember?, profile: HermesProfile?, avatarAssets: Map<String, String>, name: String, tool: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(member, profile, avatarAssets, 24, circle = true)
        Spacer(Modifier.width(10.dp))
        Text(
            if (tool.isBlank()) stringResource(R.string.channel_typing, name) else stringResource(R.string.channel_working_tool, name, toolLabel(tool)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        com.nousresearch.hermesmobile.ui.components.ThinkingIndicator(dotSize = 5.dp, spacing = 3.dp)
    }
}

/** "terminal" → "Terminal", "web_search" → "Web search". */
internal fun toolLabel(tool: String): String = tool.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

/**
 * One transcript row. A bot reply has no bubble: avatar, name and time on one line, then the words (the header is
 * dropped for a quick follow-up by the same bot). His messages sit in a soft bubble on the right with the members'
 * @mentions in colour and the time inside. Long-press a bot reply to reply to it or in its thread.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelEventRow(
    event: GroupEvent,
    member: GroupMember?,
    profile: HermesProfile?,
    avatarAssets: Map<String, String>,
    name: String,
    showAuthor: Boolean,
    handles: List<String>,
    onReplyThread: () -> Unit,
    onReplyTo: (String) -> Unit
) {
    val scheduled = RoutinePosts.isScheduled(event)
    if (!event.visibleMessage || scheduled) {
        // Routine posts keep their card, status lines stay centred: the room's existing rendering.
        RoomEventRow(event, member, onReplyThread, onReplyTo)
        return
    }
    val context = LocalContext.current
    val timeText = RoomRows.millis(event)?.let { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it)) }
    if (event.kind == "message.user") {
        val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
        val accent = MaterialTheme.colorScheme.primary
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.hermes.userBubble,
                border = BorderStroke(1.dp, MaterialTheme.hermes.userBubbleBorder),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.widthIn(max = maxBubble)
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                    event.attachments.forEach { RoomAttachment(event.eventId, it) }
                    if (event.text.isNotBlank()) {
                        val ranges = remember(event.text, handles) { ChannelView.mentionRanges(event.text, handles) }
                        Text(
                            buildAnnotatedString {
                                var at = 0
                                ranges.forEach { r ->
                                    append(event.text.substring(at, r.first))
                                    withStyle(SpanStyle(color = accent)) { append(event.text.substring(r.first, r.last + 1)) }
                                    at = r.last + 1
                                }
                                append(event.text.substring(at))
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (timeText != null) Text(timeText, Modifier.align(Alignment.End).padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    var actions by remember(event.eventId) { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = {}, onLongClick = { if (member != null || event.threadId.isNotBlank()) actions = true }),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.width(36.dp)) { if (showAuthor) MemberAvatar(member, profile, avatarAssets, 36, circle = true) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (showAuthor) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (timeText != null) Text("  $timeText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                event.attachments.forEach { RoomAttachment(event.eventId, it) }
                if (event.text.isNotBlank()) {
                    val base = com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner.current
                    val owner = remember(base, member?.profile) { base?.forPoster(member?.profile) }
                    CompositionLocalProvider(com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner provides owner) {
                        com.nousresearch.hermesmobile.ui.components.MarkdownLite(ChatMedia.renderCaptioned(event.text))
                    }
                }
            }
        }
        DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
            if (member != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_reply_to, member.handle)) },
                leadingIcon = { Icon(Icons.Default.AlternateEmail, null) },
                onClick = { actions = false; onReplyTo(member.handle) }
            )
            if (event.threadId.isNotBlank()) DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_reply_thread)) },
                leadingIcon = { Icon(Icons.Default.Forum, null) },
                onClick = { actions = false; onReplyThread() }
            )
        }
    }
}
