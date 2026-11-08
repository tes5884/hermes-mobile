package com.nousresearch.hermesmobile.ui.screens

import com.nousresearch.hermesmobile.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.data.*
import com.nousresearch.hermesmobile.ui.components.EmptyState
import com.nousresearch.hermesmobile.ui.components.HermesTextField
import com.nousresearch.hermesmobile.ui.components.HermesTopBar
import com.nousresearch.hermesmobile.ui.components.DashedDivider
import com.nousresearch.hermesmobile.ui.components.ProfileAvatar
import com.nousresearch.hermesmobile.ui.theme.hermes

/** Attach/download hooks for the open room, provided once at the app root so the panel signatures stay put. */
class GroupAttachmentActions(
    val onAttach: (List<android.net.Uri>) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onSendVoice: (java.io.File, Long) -> Unit = { f, _ -> f.delete() },
    val pendingVoice: List<PendingVoiceNote> = emptyList(),
    val onVoiceRetry: (String) -> Unit = {},
    val onVoiceAudioOnly: (String) -> Unit = {},
    val onVoiceDiscard: (String) -> Unit = {},
    val fetch: suspend (String, GroupAttachment) -> GroupAttachmentFile = { _, _ -> error("unavailable") },
    val onOpen: (String, GroupAttachment) -> Unit = { _, _ -> }
)

val LocalGroupAttachmentActions = androidx.compose.runtime.staticCompositionLocalOf { GroupAttachmentActions() }

@Composable
fun GroupsPanel(
    profiles: List<HermesProfile>,
    routedBots: List<RoutedBot>,
    capabilities: GroupCapabilities?,
    rooms: List<GroupRoom>,
    active: ActiveGroupRoom?,
    loading: Boolean,
    actionBusy: Boolean,
    error: String?,
    avatarAssets: Map<String, String>,
    onEnsureAvatar: (HermesProfile) -> Unit,
    onRefresh: () -> Unit,
    onCreate: (String, Set<String>) -> Unit,
    onOpen: (GroupRoom) -> Unit,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onReplyThread: (String?) -> Unit,
    onRename: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: (GroupPendingAction) -> Unit,
    onApprove: (GroupPendingAction, String) -> Unit,
    onDisband: () -> Unit
) {
    if (active != null) {
        GroupRoomView(
            active = active,
            profiles = profiles,
            actionBusy = actionBusy,
            avatarAssets = avatarAssets,
            onEnsureAvatar = onEnsureAvatar,
            onBack = onClose,
            onSend = onSend,
            onReplyThread = onReplyThread,
            onRename = onRename,
            onStop = onStop,
            onRetry = onRetry,
            onApprove = onApprove,
            onDisband = onDisband
        )
        return
    }

    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val subtitle = when {
                    capabilities == null -> stringResource(R.string.i18n_grp_sub_default)
                    capabilities.driver && capabilities.roomLink.enabled -> stringResource(R.string.i18n_grp_sub_roomlink)
                    capabilities.driver && capabilities.persistentProcess -> stringResource(R.string.i18n_grp_sub_persistent)
                    capabilities.driver -> stringResource(R.string.i18n_grp_sub_driver)
                    else -> stringResource(R.string.i18n_grp_sub_no_driver)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!error.isNullOrBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.i18n_refresh)) }
            IconButton(
                onClick = { create = true },
                enabled = capabilities?.driver == true && capabilities.methods.contains("groups.create") && routedBots.size >= 2
            ) { Icon(Icons.Default.GroupAdd, stringResource(R.string.ui_7888b00075)) }
        }
        DashedDivider(color = MaterialTheme.hermes.line)
        when {
            loading && rooms.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            capabilities == null && !error.isNullOrBlank() -> EmptyState(
                Icons.Default.Groups,
                stringResource(R.string.i18n_grp_unavailable),
                stringResource(R.string.i18n_grp_unavailable_hint)
            ) { TextButton(onClick = onRefresh) { Text(stringResource(R.string.ui_942087cc2d)) } }
            rooms.isEmpty() -> EmptyState(
                Icons.Default.Groups,
                stringResource(R.string.i18n_grp_empty),
                stringResource(R.string.i18n_grp_empty_hint)
            ) { Button(onClick = { create = true }, enabled = capabilities?.driver == true && routedBots.size >= 2) { Text(stringResource(R.string.ui_7888b00075)) } }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rooms, key = { it.roomId }) { room ->
                    GroupRoomRow(room, onOpen = { onOpen(room) })
                    HorizontalDivider(
                        Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = .2f)
                    )
                }
            }
        }
    }

    if (create) {
        CreateGroupDialog(
            bots = routedBots.filter { !it.profile.botHidden },
            busy = actionBusy,
            allowRemote = capabilities?.roomLink?.enabled == true,
            onDismiss = { create = false },
            onCreate = { name, members ->
                create = false
                onCreate(name, members)
            }
        )
    }
}

@Composable
private fun GroupRoomRow(room: GroupRoom, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                room.members.joinToString(" · ") { if (it.targetKind == "peer") "☁ @${it.handle}" else "@${it.handle}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text("${room.members.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onOpen) { Icon(Icons.Default.ChevronRight, stringResource(R.string.ui_ed077f3d81)) }
    }
}

@Composable
private fun CreateGroupDialog(
    bots: List<RoutedBot>,
    busy: Boolean,
    allowRemote: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, Set<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val atCap = selected.size >= 6
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ui_7888b00075)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HermesTextField(name, { name = it }, stringResource(R.string.i18n_grp_room_name), placeholder = stringResource(R.string.i18n_grp_room_name_hint))
                Text(stringResource(R.string.ui_2745ea8b0f), style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(bots, key = { it.routeKey }) { bot ->
                        val profile = bot.profile
                        val checked = bot.routeKey in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val routeEnabled = bot.activeConnection || allowRemote
                            Checkbox(
                                checked = checked,
                                enabled = routeEnabled && (checked || !atCap),
                                onCheckedChange = { value ->
                                    selected = if (value) selected + bot.routeKey else selected - bot.routeKey
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.label)
                                Text(
                                    "@${bot.handle} · ${bot.connectionLabel}${if (bot.activeConnection) " · " + stringResource(R.string.i18n_this_gateway) else if (!allowRemote) " · " + stringResource(R.string.i18n_grp_roomlink_na) else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!bot.activeConnection) Icon(Icons.Default.Cloud, stringResource(R.string.i18n_remote_gateway), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    stringResource(R.string.i18n_grp_roomlink_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.trim().isNotBlank() && selected.size in 2..6,
                onClick = { onCreate(name.trim(), selected) }
            ) { Text(stringResource(if (busy) R.string.i18n_creating else R.string.i18n_create)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.ui_19766ed6cc)) } }
    )
}

@Composable
private fun GroupRoomView(
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
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var disband by remember { mutableStateOf(false) }
    // Reverse-layout list like the 1:1 transcript: index 0 is the newest row, so the room opens at the
    // latest message, stays pinned there as events arrive, and never moves a reader who scrolled up.
    val listState = remember(active.room.roomId) { androidx.compose.foundation.lazy.LazyListState() }
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
    val reversedRows = remember(visibleEvents, dividerSeq) { RoomRows.build(visibleEvents, newMessagesBeforeSeq = dividerSeq).asReversed() }
    LaunchedEffect(roomKey, visibleEvents.isNotEmpty()) {
        if (visibleEvents.isNotEmpty() && !anchored) {
            anchored = true
            dividerSeq = RoomRead.firstUnreadSeq(visibleEvents, initialSaved)
            if (dividerSeq == null) ready = true
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(dividerSeq) {
        val target = dividerSeq ?: return@LaunchedEffect
        val idx = reversedRows.indexOfFirst { it is RoomRow.NewMessages }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            // The divider now sits at the bottom edge; move the list back toward the newest so it lands near the top.
            val h = listState.layoutInfo.viewportSize.height
            if (h > 0) listState.scrollBy(-(h - with(density) { 96.dp.toPx() }).coerceAtLeast(0f))
        }
        ready = true
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
    LaunchedEffect(reversedRows.size) { if (pinActive) listState.scrollToItem(0) }
    val membersById = remember(active.room.members) { active.room.members.associateBy { it.memberId } }
    val profilesByName = remember(profiles) { profiles.associateBy { it.name } }

    // An open room is a view inside Bots: back closes it, it doesn't leave the page.
    androidx.activity.compose.BackHandler(onBack = onBack)
    val attachActions0 = LocalGroupAttachmentActions.current
    Column(Modifier.fillMaxSize().imePadding()) {
        HermesTopBar(
            active.room.name,
            pluralStringResource(R.plurals.i18n_bots_count, active.room.members.size, active.room.members.size) + when {
                active.driver.working -> " · " + stringResource(R.string.i18n_grp_working)
                active.driver.blocked -> " · " + stringResource(R.string.i18n_grp_attention)
                active.driver.running -> " · " + stringResource(R.string.i18n_grp_ready)
                else -> ""
            },
            leading = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.a21_back)) } },
            actions = {
                if (active.driver.working) IconButton(onClick = onStop, enabled = !actionBusy) { Icon(Icons.Default.StopCircle, stringResource(R.string.i18n_grp_stop)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.i18n_grp_actions)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_3064d79a29)) },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; rename = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_5a7525fd26)) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = { menu = false; disband = true }
                        )
                    }
                }
            }
        )
        DashedDivider(color = MaterialTheme.hermes.line)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            active.room.members.forEach { member ->
                val profile = profilesByName[member.profile]
                if (profile != null) LaunchedEffect(profile.name, profile.hasAvatar) { onEnsureAvatar(profile) }
                AssistChip(
                    onClick = { input = appendMention(input, member.handle) },
                    label = { Text("@${member.handle}", maxLines = 1) },
                    leadingIcon = if (profile != null) {
                        { ProfileAvatar(profile, size = 22, avatarDataUrl = avatarAssets[profile.name]) }
                    } else null
                )
            }
        }

        if (active.driver.pendingActions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                active.driver.pendingActions.forEach { action ->
                    PendingRoomActionCard(action, membersById[action.memberId], actionBusy, onRetry, onApprove)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val pendingNotes = attachActions0.pendingVoice.filter { it.roomId == active.room.roomId }
        if (visibleEvents.isEmpty() && pendingNotes.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.ui_ea79140946), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val awayFromBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (awayFromBottom) 52.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reversed list: index 0 is the newest, so his pending notes sit at the very bottom.
                items(pendingNotes.asReversed(), key = { "p:" + it.id }) { note ->
                    PendingVoiceBubble(note, { attachActions0.onVoiceRetry(note.id) }, { attachActions0.onVoiceAudioOnly(note.id) }, { attachActions0.onVoiceDiscard(note.id) })
                }
                items(reversedRows, key = { it.key }) { row ->
                    when (row) {
                        is RoomRow.Day -> DaySeparatorRow(dayLabel(row.millis))
                        is RoomRow.NewMessages -> NewMessagesRow()
                        is RoomRow.Event -> {
                            val event = row.event
                            RoomEventRow(
                                event = event,
                                member = membersById[event.memberId],
                                onReplyThread = { event.threadId.takeIf(String::isNotBlank)?.let(onReplyThread) },
                                onReplyTo = { handle -> input = appendMention(input, handle) }
                            )
                        }
                    }
                }
            }
            if (awayFromBottom) {
                val chipScope = rememberCoroutineScope()
                Surface(
                    onClick = { chipScope.launch { listState.animateScrollToItem(0) } },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                ) {
                    Text(
                        RoomRead.unreadCount(visibleEvents, seenSeq).let { n -> if (n > 0) stringResource(R.string.group_new_chip, n) else "↓" },
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            }
        }

        DashedDivider(color = MaterialTheme.hermes.line)
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
        val attachActions = LocalGroupAttachmentActions.current
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
        ) { uris -> if (uris.isNotEmpty()) attachActions.onAttach(uris) }
        val takePhoto = rememberTakePhoto { attachActions.onAttach(listOf(it)) }
        var attachMenu by remember { mutableStateOf(false) }
        AttachmentStrip(active.composerAttachments, attachActions.onRemove)
        val context = LocalContext.current
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        var recorderLimit by remember { mutableStateOf(false) }
        val recorder = remember(active.room.roomId) { com.nousresearch.hermesmobile.features.VoiceNoteRecorder(context) { recorderLimit = true } }
        var recordingMs by remember { mutableStateOf<Long?>(null) }
        var locked by remember { mutableStateOf(false) }
        var dragX by remember { mutableFloatStateOf(0f) }
        var levels by remember { mutableStateOf<List<Float>>(emptyList()) }
        var micError by remember { mutableStateOf<String?>(null) }
        val startFailedText = stringResource(R.string.group_voice_start_failed)
        val micPermissionText = stringResource(R.string.a22_voice_permission)
        var tooltip by remember { mutableStateOf(false) }
        val tick: () -> Unit = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
        fun resetRecording() { recordingMs = null; locked = false; dragX = 0f; levels = emptyList() }
        fun beginRecording(): Boolean {
            micError = null
            val ok = runCatching { recorder.start(); recordingMs = 0L; levels = emptyList(); locked = false; dragX = 0f }
                .onFailure { micError = it.message ?: startFailedText }.isSuccess
            if (ok) tick()
            return ok
        }
        fun finishRecording() {
            resetRecording()
            recorder.stop()?.let { (file, ms) -> attachActions.onSendVoice(file, ms); pinTick++ }
        }
        fun cancelRecording() { recorder.cancel(); resetRecording() }
        val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted -> if (!granted) micError = micPermissionText } // allowed: hold again
        fun hasMic() = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        LaunchedEffect(recordingMs != null) {
            while (recordingMs != null) {
                recordingMs = recorder.elapsedMs()
                levels = VoiceNotes.pushLevel(levels, VoiceNotes.level(recorder.amplitude()))
                kotlinx.coroutines.delay(50)
            }
        }
        LaunchedEffect(recorderLimit) { if (recorderLimit) { recorderLimit = false; if (recorder.active) finishRecording() } }
        LaunchedEffect(tooltip) { if (tooltip) { kotlinx.coroutines.delay(2000); tooltip = false } }
        DisposableEffect(recorder) { onDispose { recorder.cancel() } }
        micError?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }

        val cancelWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        val micCallbacks = remember(recorder) {
            HoldMicCallbacks(
                onPress = { if (hasMic()) beginRecording() else { micPermission.launch(android.Manifest.permission.RECORD_AUDIO); false } },
                onDrag = { dx, _ -> dragX = dx },
                onLock = { locked = true; dragX = 0f; tick() },
                onRelease = { heldMs, cancelled ->
                    when {
                        !recorder.active -> {}                       // the 10 min cap already sent it
                        cancelled -> { cancelRecording(); tick() }
                        VoiceNotes.tooShort(heldMs) -> { cancelRecording(); tooltip = true }
                        else -> finishRecording()
                    }
                },
                onTalkBackToggle = {
                    when {
                        recorder.active -> finishRecording()
                        hasMic() -> { if (beginRecording()) locked = true }
                        else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
        val recording = recordingMs != null
        val showMic = input.isBlank() && active.composerAttachments.isEmpty()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recording) {
                RecordingBar(recordingMs ?: 0L, levels, locked, dragX, onCancel = { cancelRecording() }, onSend = { finishRecording() }, modifier = Modifier.weight(1f))
            } else {
                Box {
                    FilledTonalIconButton(enabled = !actionBusy, onClick = { attachMenu = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.AttachFile, stringResource(R.string.chat_attach))
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_attach)) },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { attachMenu = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_take_photo)) },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                            onClick = { attachMenu = false; takePhoto() })
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    placeholder = { Text(stringResource(R.string.group_composer_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
            if (!(recording && locked)) {
                Spacer(Modifier.width(8.dp))
                if (showMic || recording) {
                    HoldMicButton(
                        enabled = true, recording = recording, cancelWidthPx = cancelWidthPx, callbacks = micCallbacks,
                        tooltip = tooltip, showLockHint = recording && !locked
                    )
                } else {
                    FilledIconButton(
                        enabled = !actionBusy,
                        modifier = Modifier.size(44.dp),
                        onClick = { val value = input.trim(); input = ""; onSend(value); pinTick++ }
                    ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.i18n_send)) }
                }
            }
        }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RoomEventRow(
    event: GroupEvent,
    member: GroupMember?,
    onReplyThread: () -> Unit,
    onReplyTo: (String) -> Unit
) {
    if (event.visibleMessage) {
        val user = event.kind == "message.user"
        val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 18.dp, topEnd = 18.dp,
            bottomEnd = if (user) 4.dp else 18.dp, bottomStart = if (user) 18.dp else 4.dp
        )
        val canThread = !user && event.threadId.isNotBlank()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Surface(
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                shape = shape,
                modifier = Modifier.widthIn(max = maxBubble).clip(shape)
                    .then(if (canThread) Modifier.combinedClickable(onClick = {}, onLongClickLabel = stringResource(R.string.ui_a236732715), onLongClick = onReplyThread) else Modifier)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val timeText = RoomRows.millis(event)?.let { android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(it)) }
                    if (!user) {
                        Text(
                            member?.let { "@${it.handle} · ${it.label}" } ?: event.actor.displayName.ifBlank { event.actor.id },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    if (event.text.isNotBlank()) {
                        val base = com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner.current
                        val owner = remember(base, member?.profile) { base?.let { b -> member?.profile?.takeIf { it.isNotBlank() }?.let { b.copy(profile = it) } ?: b } }
                        androidx.compose.runtime.CompositionLocalProvider(com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner provides owner) {
                            com.nousresearch.hermesmobile.ui.components.MarkdownLite(com.nousresearch.hermesmobile.data.ChatMedia.render(event.text))
                        }
                    }
                    event.attachments.forEach { RoomAttachment(event.eventId, it) }
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (!user && member != null) {
                            Text(
                                "↩ " + stringResource(R.string.group_reply_short),
                                Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).clickable { onReplyTo(member.handle) }.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        if (timeText != null) Text(timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        val label = roomEventStatusText(event, LocalContext.current) ?: return
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (event.kind == "turn.failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (event.kind.startsWith("turn.")) com.nousresearch.hermesmobile.ui.theme.HermesMono else null
            )
        }
    }
}

@Composable
private fun PendingRoomActionCard(
    action: GroupPendingAction,
    member: GroupMember?,
    busy: Boolean,
    onRetry: (GroupPendingAction) -> Unit,
    onApprove: (GroupPendingAction, String) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            when (action.kind) {
                "approval" -> {
                    Text(stringResource(R.string.i18n_grp_approval, member?.handle ?: action.memberId), style = MaterialTheme.typography.titleSmall)
                    if (action.command.isNotBlank()) Text(action.command, style = MaterialTheme.typography.bodySmall, fontFamily = com.nousresearch.hermesmobile.ui.theme.HermesMono)
                    if (action.description.isNotBlank()) Text(action.description, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { onApprove(action, "once") }) { Text(stringResource(R.string.ui_168511d24d)) }
                        OutlinedButton(enabled = !busy, onClick = { onApprove(action, "deny") }) { Text(stringResource(R.string.ui_05a2d7332e)) }
                    }
                }
                "retry" -> {
                    Text(stringResource(R.string.ui_94efa66d7a), style = MaterialTheme.typography.titleSmall)
                    Text(action.taskId, style = MaterialTheme.typography.bodySmall, fontFamily = com.nousresearch.hermesmobile.ui.theme.HermesMono)
                    Button(enabled = !busy, onClick = { onRetry(action) }) { Text(stringResource(R.string.ui_942087cc2d)) }
                }
                else -> Text(stringResource(R.string.i18n_grp_action, action.kind), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun shouldRenderRoomEvent(event: GroupEvent): Boolean = when (event.kind) {
    "message.user", "message.member", "turn.failed", "turn.cancelled", "turn.deferred", "room.stop_requested" -> true
    // Only the success "Room settled" line is dropped; any other room status stays.
    "room.activity" -> !event.payload.optString("status").equals("settled", true)
    else -> false
}

private fun roomEventStatusText(event: GroupEvent, context: android.content.Context): String? = when (event.kind) {
    "turn.failed" -> context.getString(R.string.i18n_grp_turn_failed, event.payload.optString("error").ifBlank { context.getString(R.string.i18n_unknown_error) })
    "turn.cancelled" -> context.getString(R.string.i18n_grp_turn_cancelled, event.payload.optString("reason")).trimEnd(':', ' ')
    "turn.deferred" -> context.getString(R.string.i18n_grp_turn_deferred, event.payload.optString("reason")).trimEnd(':', ' ')
    "room.activity" -> event.payload.optString("status").takeIf { it.isNotBlank() && !it.equals("settled", true) }?.let { context.getString(R.string.i18n_grp_room_status, it) }
    "room.stop_requested" -> context.getString(R.string.i18n_grp_stop_requested)
    else -> null
}

private fun appendMention(text: String, handle: String): String {
    val prefix = text.trimEnd()
    return if (prefix.isBlank()) "@$handle " else "$prefix @$handle "
}


@Composable
private fun RoomAttachment(eventId: String, attachment: GroupAttachment) {
    val actions = LocalGroupAttachmentActions.current
    if (attachment.kind == "image") {
        val state = produceState<Result<android.graphics.Bitmap>?>(null, eventId, attachment.attachmentId) {
            value = runCatching {
                val file = actions.fetch(eventId, attachment)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { decodeScaled(file.bytes) }
                    ?: error("Not a readable image")
            }
        }
        val result = state.value
        val bitmap = result?.getOrNull()
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(max = 320.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { actions.onOpen(eventId, attachment) }
            )
            return
        }
        if (result == null) {
            Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            return
        }
        // Failed to load: fall through to a tappable card so the file is still reachable.
    }
    if (VoiceNotes.isVoiceNote(attachment)) {
        VoiceNotePlayer(eventId, attachment)
        return
    }
    OutlinedCard(
        onClick = { actions.onOpen(eventId, attachment) },
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (attachment.kind) { "image" -> Icons.Default.Image; "pdf" -> Icons.Default.PictureAsPdf; else -> Icons.Default.AttachFile },
                null, Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (attachment.size > 0) Text(formatAttachmentSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.Download, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

private fun decodeScaled(bytes: ByteArray, maxEdge: Int = 1280): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
}


/** "New messages" marker: sits above the first unread message and stays until the room is closed. */
@Composable
private fun NewMessagesRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Text(stringResource(R.string.group_new_messages), Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
    }
}


/** Play button + duration for a voice note; the audio is fetched on the first tap, then played from a cache file. */
@Composable
private fun VoiceNotePlayer(eventId: String, attachment: GroupAttachment) {
    val actions = LocalGroupAttachmentActions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(attachment.attachmentId) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(attachment.attachmentId) { mutableStateOf(false) }
    var loading by remember(attachment.attachmentId) { mutableStateOf(false) }
    var failed by remember(attachment.attachmentId) { mutableStateOf(false) }
    var positionSec by remember(attachment.attachmentId) { mutableIntStateOf(0) }
    val totalSec = VoiceNotes.durationSeconds(attachment.name) ?: player?.let { it.duration / 1000 } ?: 0
    DisposableEffect(attachment.attachmentId) { onDispose { player?.release(); player = null } }
    LaunchedEffect(playing) {
        while (playing) { positionSec = (player?.currentPosition ?: 0) / 1000; kotlinx.coroutines.delay(300) }
    }
    fun toggle() {
        val p = player
        if (p != null) {
            if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
            return
        }
        if (loading) return
        loading = true; failed = false
        scope.launch {
            runCatching {
                val file = actions.fetch(eventId, attachment)
                val tmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File.createTempFile("hermes-play-", ".m4a", context.cacheDir).apply { writeBytes(file.bytes); deleteOnExit() }
                }
                val mp = android.media.MediaPlayer()
                mp.setDataSource(tmp.path)
                mp.setOnCompletionListener { playing = false; positionSec = 0; it.seekTo(0) }
                mp.prepare()
                tmp.delete()
                player = mp; mp.start(); playing = true
            }.onFailure { failed = true }
            loading = false
        }
    }
    Row(Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { toggle() }, enabled = !loading) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.group_voice_pause else R.string.group_voice_play))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (failed) stringResource(R.string.group_voice_load_failed) else if (playing || positionSec > 0) "${VoiceNotes.clock(positionSec)} / ${VoiceNotes.clock(totalSec)}" else VoiceNotes.clock(totalSec),
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
