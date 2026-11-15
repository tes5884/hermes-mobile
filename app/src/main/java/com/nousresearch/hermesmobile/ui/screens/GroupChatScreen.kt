package com.nousresearch.hermesmobile.ui.screens

import com.nousresearch.hermesmobile.ui.components.forPoster
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
    onDisband: () -> Unit,
    unread: Map<String, Int> = emptyMap(),
    previews: Map<String, RoomPreview> = emptyMap()
) {
    if (active != null) {
        ChannelRoomView(
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
                    GroupRoomRow(room, unread[room.roomId] ?: 0, previews[room.roomId], onOpen = { onOpen(room) })
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
private fun GroupRoomRow(room: GroupRoom, unread: Int, preview: RoomPreview?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Medium)
            Text(
                preview?.let { RoomPreviews.text(it, stringResource(R.string.room_you_prefix)) } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (preview != null) Text(relativeDate(preview.createdAt.toLong()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (unread > 0) { Spacer(Modifier.height(4.dp)); UnreadPill(unread) }
        }
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

/** Rename and Disband Group Chat, at the foot of an open channel's member menu. */
@Composable
internal fun ChannelAdminItems(onRename: () -> Unit, onDisband: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(R.string.ui_3064d79a29)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = onRename)
    DropdownMenuItem(text = { Text(stringResource(R.string.ui_5a7525fd26)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = onDisband)
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
        val scheduled = com.nousresearch.hermesmobile.data.RoutinePosts.isScheduled(event)
        val user = event.kind == "message.user" && !scheduled
        val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.8f).dp
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 18.dp, topEnd = 18.dp,
            bottomEnd = if (user) 4.dp else 18.dp, bottomStart = if (user) 18.dp else 4.dp
        )
        val canThread = !user && event.threadId.isNotBlank()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            Surface(
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (scheduled) .3f else .55f),
                shape = shape,
                border = if (scheduled) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                modifier = Modifier.widthIn(max = maxBubble).clip(shape)
                    .then(if (canThread) Modifier.combinedClickable(onClick = {}, onLongClick = onReplyThread) else Modifier)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val timeText = RoomRows.millis(event)?.let { android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(it)) }
                    if (scheduled) {
                        com.nousresearch.hermesmobile.ui.components.RoutinePostHeader(com.nousresearch.hermesmobile.data.RoutinePosts.name(event.actor.displayName))
                        Spacer(Modifier.height(3.dp))
                    } else if (!user) {
                        Text(
                            member?.let { "@${it.handle} · ${it.label}" } ?: event.actor.displayName.ifBlank { event.actor.id },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    event.attachments.forEach { RoomAttachment(event.eventId, it) }
                    if (event.text.isNotBlank()) {
                        val base = com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner.current
                        val owner = remember(base, member?.profile) { base?.forPoster(member?.profile) }
                        androidx.compose.runtime.CompositionLocalProvider(com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner provides owner) {
                            com.nousresearch.hermesmobile.ui.components.MarkdownLite(com.nousresearch.hermesmobile.data.ChatMedia.renderCaptioned(event.text))
                        }
                    }
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
internal fun PendingRoomActionCard(
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
                    if (action.command.isNotBlank()) com.nousresearch.hermesmobile.ui.components.CollapsibleCommand(action.command)
                    if (action.description.isNotBlank()) Text(action.description, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

internal fun shouldRenderRoomEvent(event: GroupEvent): Boolean = when (event.kind) {
    "message.user", "message.member", "turn.failed", "turn.cancelled", "turn.deferred", "room.stop_requested" -> true
    // Only the success "Room settled" line is dropped; any other room status stays.
    "room.activity" -> !event.payload.optString("status").equals("settled", true)
    else -> false
}

internal fun roomEventStatusText(event: GroupEvent, context: android.content.Context): String? = when (event.kind) {
    "turn.failed" -> context.getString(R.string.i18n_grp_turn_failed, event.payload.optString("error").ifBlank { context.getString(R.string.i18n_unknown_error) })
    "turn.cancelled" -> context.getString(R.string.i18n_grp_turn_cancelled, event.payload.optString("reason")).trimEnd(':', ' ')
    "turn.deferred" -> context.getString(R.string.i18n_grp_turn_deferred, event.payload.optString("reason")).trimEnd(':', ' ')
    "room.activity" -> event.payload.optString("status").takeIf { it.isNotBlank() && !it.equals("settled", true) }?.let { context.getString(R.string.i18n_grp_room_status, it) }
    "room.stop_requested" -> context.getString(R.string.i18n_grp_stop_requested)
    else -> null
}

internal fun appendMention(text: String, handle: String): String {
    val prefix = text.trimEnd()
    return if (prefix.isBlank()) "@$handle " else "$prefix @$handle "
}


@Composable
internal fun RoomAttachment(eventId: String, attachment: GroupAttachment) {
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
            if (failed) "Couldn't load audio" else if (playing || positionSec > 0) "${VoiceNotes.clock(positionSec)} / ${VoiceNotes.clock(totalSec)}" else VoiceNotes.clock(totalSec),
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


/** "New messages" marker: sits above the first unread message and stays until the room is closed. */
@Composable
internal fun NewMessagesRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Text(stringResource(R.string.group_new_messages), Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
    }
}

/** Accent pill with a count; a null count is the 8dp dot (bots, whose server flag has no number). Accent = unread, never the green busy dot. */
@Composable
internal fun UnreadPill(count: Int?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    if (count == null) {
        Box(modifier.size(8.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
    } else {
        Box(
            modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).background(accent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (count > 99) "99+" else "$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
