package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.BotHome
import com.nousresearch.hermesmobile.data.GroupRoom
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.LiveSessions
import com.nousresearch.hermesmobile.data.RoomPreview
import com.nousresearch.hermesmobile.data.RoutedBot
import com.nousresearch.hermesmobile.data.SessionDot
import com.nousresearch.hermesmobile.data.VoiceNotes
import com.nousresearch.hermesmobile.ui.components.HermesSearchField
import com.nousresearch.hermesmobile.ui.components.ProfileAvatar
import com.nousresearch.hermesmobile.ui.theme.HermesTokens
import com.nousresearch.hermesmobile.ui.theme.hermes

/** What a home row can do; the long press keeps the old roster actions (pin, edit, delete). */
internal class BotHomeActions(
    val onMenu: () -> Unit,
    val onOpen: (HermesProfile) -> Unit,
    val onSwitchConnection: (String) -> Unit,
    val onEdit: (HermesProfile) -> Unit,
    val onDelete: (HermesProfile) -> Unit,
    val onTogglePin: (HermesProfile) -> Unit,
    val onCreateBot: () -> Unit,
    val onOpenChannel: (GroupRoom) -> Unit,
    val onAllChannels: () -> Unit,
    /** A voice note recorded on home for [HermesProfile]: open its chat and send it there. */
    val onTalk: (HermesProfile, java.io.File, Long) -> Unit,
    val onEnsureAvatar: (HermesProfile) -> Unit
)

/** Orange = needs you; the only colour on home besides the small blue "working" dot. */
private val NeedsYou = HermesTokens.Orange

/**
 * Bot-first home: bots only, pinned chief on top, departments in a fixed order, then channels, and a floating
 * hold-to-talk button for the chief. Status comes from the liveness dots and the canonical chat's unread flag.
 */
@Composable
internal fun BotHomeContent(
    bots: List<RoutedBot>,
    sessionDots: Map<String, SessionDot>,
    botUnread: Set<String>,
    avatarAssets: Map<String, String>,
    rooms: List<GroupRoom>,
    roomUnread: Map<String, Int>,
    roomPreviews: Map<String, RoomPreview>,
    loading: Boolean,
    actions: BotHomeActions,
    sectionOf: (RoutedBot) -> String
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val shown = remember(bots, showHidden) { if (showHidden) bots.map { it.copy(profile = it.profile.unhidden()) } else bots }
    val statuses = remember(shown, sessionDots, botUnread) {
        shown.associate { b ->
            val dot = if (b.activeConnection) LiveSessions.dotFor(sessionDots, b.profile.canonicalSession) else SessionDot.NONE
            b.routeKey to BotHome.status(dot, b.profile.name in botUnread && b.activeConnection, b.profile.activityPreview)
        }
    }
    val summary = remember(statuses, shown) { BotHome.summary(shown.filter { !it.profile.botHidden }.mapNotNull { statuses[it.routeKey] }) }
    val pinned = remember(shown) { BotHome.pinned(shown) }
    val sections = remember(shown, query) {
        if (query.isNotBlank()) {
            val q = query.trim()
            listOf("" to shown.filter { !it.profile.botHidden && (it.label.contains(q, true) || it.profile.name.contains(q, true)) }
                .sortedBy { it.label.lowercase() })
        } else BotHome.sections(shown, sectionOf)
    }
    val chief = remember(shown) { BotHome.chief(shown) }
    val hiddenCount = bots.count { it.profile.botHidden }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.botapp_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, stringResource(R.string.botapp_search))
                        }
                        Surface(
                            onClick = actions.onMenu, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, stringResource(R.string.botapp_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    if (searching) {
                        Spacer(Modifier.height(8.dp))
                        HermesSearchField(query, { query = it }, stringResource(R.string.botapp_search), Modifier.fillMaxWidth().padding(end = 8.dp))
                    } else {
                        val dim = MaterialTheme.colorScheme.onSurfaceVariant
                        Text(buildAnnotatedString {
                            if (summary.needYou > 0) {
                                withStyle(SpanStyle(color = NeedsYou, fontWeight = FontWeight.SemiBold)) { append(pluralStringResource(R.plurals.botapp_summary_need, summary.needYou, summary.needYou)) }
                                withStyle(SpanStyle(color = dim)) { append(" · ") }
                            }
                            withStyle(SpanStyle(color = dim)) {
                                append(pluralStringResource(R.plurals.botapp_summary_working, summary.working, summary.working))
                                append(" · ")
                                append(pluralStringResource(R.plurals.botapp_summary_idle, summary.idle, summary.idle))
                            }
                        }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (loading && bots.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            if (query.isBlank()) items(pinned, key = { "pin:" + it.routeKey }) { bot ->
                if (bot.activeConnection) LaunchedEffect(bot.profile.name, bot.profile.hasAvatar) { actions.onEnsureAvatar(bot.profile) }
                PinnedBotCard(bot, statuses[bot.routeKey], avatarAssets[bot.profile.name].takeIf { bot.activeConnection }, use24h, actions)
            }
            sections.forEach { (name, members) ->
                if (members.isEmpty()) return@forEach
                if (name.isNotEmpty()) item(key = "h:$name") { HomeSectionLabel(name) }
                items(members, key = { it.routeKey }) { bot ->
                    if (bot.activeConnection) LaunchedEffect(bot.profile.name, bot.profile.hasAvatar) { actions.onEnsureAvatar(bot.profile) }
                    BotHomeRow(bot, statuses[bot.routeKey], avatarAssets[bot.profile.name].takeIf { bot.activeConnection }, use24h, actions)
                }
            }
            if (query.isBlank()) {
                item(key = "h:channels") { HomeSectionLabel(stringResource(R.string.botapp_channels)) }
                items(rooms.take(6), key = { "room:" + it.roomId }) { room ->
                    ChannelHomeRow(room, bots, avatarAssets, roomUnread[room.roomId] ?: 0, roomPreviews[room.roomId], use24h) { actions.onOpenChannel(room) }
                }
                item(key = "more") {
                    Column(Modifier.padding(top = 4.dp)) {
                        HomeLinkRow(Icons.Default.Forum, stringResource(if (rooms.isEmpty()) R.string.botapp_new_channel else R.string.botapp_all_channels), actions.onAllChannels)
                        HomeLinkRow(Icons.Default.Add, stringResource(R.string.botapp_new_bot), actions.onCreateBot)
                        if (hiddenCount > 0) HomeLinkRow(
                            if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(if (showHidden) R.string.botapp_hide_hidden else R.string.botapp_show_hidden)
                        ) { showHidden = !showHidden }
                    }
                }
            }
        }
        if (chief != null) TalkToButton(
            chief, avatarAssets[chief.profile.name],
            onTap = { actions.onOpen(chief.profile) },
            onSend = { f, ms -> actions.onTalk(chief.profile, f, ms) },
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}

/** Hidden bots shown on request lose the flag for the list only, so they sit in their department. */
private fun HermesProfile.unhidden(): HermesProfile {
    val meta = uiMeta ?: return this
    val bots = meta.optJSONObject("hermes-bots") ?: return this
    if (!bots.optBoolean("hidden", false)) return this
    val copy = org.json.JSONObject(meta.toString())
    copy.getJSONObject("hermes-bots").put("hidden", false)
    return copy(uiMeta = copy)
}

@Composable
private fun HomeSectionLabel(name: String) {
    Text(
        name.uppercase(), Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = TextUnit(1.2f, TextUnitType.Sp)
    )
}

@Composable
private fun HomeLinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The status line: orange shield for an approval/question, blue dot while working, dark when unread, grey idle. */
@Composable
private fun StatusLine(status: BotHome.Status?, offlineLabel: String?, big: Boolean = false) {
    val style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (text, color, weight) = when {
            offlineLabel != null -> Triple(offlineLabel, dim, FontWeight.Normal)
            status == null -> Triple(stringResource(R.string.botapp_idle), dim, FontWeight.Normal)
            status.kind == BotHome.Kind.NEEDS_OK -> Triple(stringResource(R.string.botapp_needs_ok), NeedsYou, FontWeight.SemiBold)
            status.kind == BotHome.Kind.WORKING -> Triple(
                if (status.text.isBlank()) stringResource(R.string.a21_working) else stringResource(R.string.botapp_working_on, status.text),
                dim, FontWeight.Normal
            )
            status.kind == BotHome.Kind.WAITING -> Triple(status.text.ifBlank { stringResource(R.string.botapp_unread) }, MaterialTheme.colorScheme.onSurface, FontWeight.Medium)
            else -> Triple(status.text.ifBlank { stringResource(R.string.botapp_idle) }, dim, FontWeight.Normal)
        }
        if (offlineLabel == null && status?.kind == BotHome.Kind.NEEDS_OK) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(if (big) 18.dp else 16.dp), tint = NeedsYou)
            Spacer(Modifier.width(5.dp))
        } else if (offlineLabel == null && status?.kind == BotHome.Kind.WORKING) {
            Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = style, color = color, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Right column: time on top, the orange unread mark under it. */
@Composable
private fun TimeAndBadge(epochSec: Long, needsYou: Boolean, use24h: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        Text(BotHome.timeLabel(epochSec, use24h = use24h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (needsYou) Box(Modifier.size(12.dp).background(NeedsYou, CircleShape)) else Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AvatarWithDot(profile: HermesProfile, avatar: String?, size: Int, status: BotHome.Status?) {
    Box {
        ProfileAvatar(profile, size = size, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape))
        val dotColor = when (status?.kind) {
            BotHome.Kind.WORKING -> MaterialTheme.colorScheme.primary
            BotHome.Kind.NEEDS_OK -> NeedsYou
            else -> null
        }
        if (dotColor != null) Box(
            Modifier.align(Alignment.BottomEnd).size(14.dp).border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                .padding(2.dp).background(dotColor, CircleShape)
        )
    }
}

@Composable
private fun NameAndRole(bot: RoutedBot, big: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            bot.label, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
        )
        val role = BotHome.role(bot.profile)
        if (role.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(role, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun offlineLabel(bot: RoutedBot): String? = when {
    !bot.activeConnection -> stringResource(R.string.botapp_on_gateway, bot.connectionLabel)
    !(bot.reachable && bot.profile.gatewayRunning) -> stringResource(R.string.bot_offline)
    else -> null
}

private fun needsYou(status: BotHome.Status?) = status?.kind == BotHome.Kind.NEEDS_OK || status?.kind == BotHome.Kind.WAITING

@Composable
private fun PinnedBotCard(bot: RoutedBot, status: BotHome.Status?, avatar: String?, use24h: Boolean, actions: BotHomeActions) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.hermes.line),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).clip(RoundedCornerShape(22.dp))
            .combinedClickable(onClick = { if (bot.activeConnection) actions.onOpen(bot.profile) else actions.onSwitchConnection(bot.connectionId) }, onLongClick = { menu = true })
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarWithDot(bot.profile, avatar, 64, status)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                NameAndRole(bot, big = true)
                Spacer(Modifier.height(4.dp))
                StatusLine(status, offlineLabel(bot), big = true)
            }
            Spacer(Modifier.width(8.dp))
            TimeAndBadge(bot.profile.activityAt, needsYou(status), use24h)
        }
        BotRowMenu(bot, menu, { menu = false }, actions)
    }
}

@Composable
private fun BotHomeRow(bot: RoutedBot, status: BotHome.Status?, avatar: String?, use24h: Boolean, actions: BotHomeActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { if (bot.activeConnection) actions.onOpen(bot.profile) else actions.onSwitchConnection(bot.connectionId) }, onLongClick = { menu = true })
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarWithDot(bot.profile, avatar, 52, null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                NameAndRole(bot, big = false)
                Spacer(Modifier.height(2.dp))
                StatusLine(status, offlineLabel(bot))
            }
            Spacer(Modifier.width(8.dp))
            TimeAndBadge(bot.profile.activityAt, needsYou(status), use24h)
        }
        BotRowMenu(bot, menu, { menu = false }, actions)
    }
}

/** Long press: the roster actions that used to sit behind each row's ⋮ (no side chats: a bot is one chat). */
@Composable
private fun BotRowMenu(bot: RoutedBot, expanded: Boolean, onDismiss: () -> Unit, actions: BotHomeActions) {
    val profile = bot.profile
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!bot.activeConnection) {
            DropdownMenuItem(text = { Text(stringResource(R.string.i18n_bots_switch_to, bot.connectionLabel)) }, leadingIcon = { Icon(Icons.Default.Dns, null) },
                onClick = { onDismiss(); actions.onSwitchConnection(bot.connectionId) })
            return@DropdownMenu
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_885b06da76)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
            onClick = { onDismiss(); actions.onOpen(profile) })
        DropdownMenuItem(text = { Text(stringResource(if (profile.botPinned) R.string.bots_unpin else R.string.bots_pin)) }, leadingIcon = { Icon(Icons.Default.PushPin, null) },
            onClick = { onDismiss(); actions.onTogglePin(profile) })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_c0cd1fd055)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onDismiss(); actions.onEdit(profile) })
        if (!profile.isDefault) DropdownMenuItem(text = { Text(stringResource(R.string.ui_f53f5a6eb1)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
            onClick = { onDismiss(); actions.onDelete(profile) })
    }
}

@Composable
private fun ChannelHomeRow(
    room: GroupRoom, bots: List<RoutedBot>, avatars: Map<String, String>, unread: Int, preview: RoomPreview?, use24h: Boolean, onClick: () -> Unit
) {
    val members = remember(room, bots) {
        room.members.mapNotNull { m -> bots.firstOrNull { it.activeConnection && it.profile.name == m.profile }?.profile ?: HermesProfile(name = m.profile, displayName = m.displayName) }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp)) {
            members.getOrNull(1)?.let { ProfileAvatar(it, size = 36, avatarDataUrl = avatars[it.name], modifier = Modifier.align(Alignment.BottomEnd).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.background, CircleShape)) }
            members.getOrNull(0)?.let { ProfileAvatar(it, size = 36, avatarDataUrl = avatars[it.name], modifier = Modifier.align(Alignment.TopStart).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.background, CircleShape)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name.ifBlank { members.joinToString(", ") { it.label } }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val line = preview?.let { p -> listOf(p.sender, p.line).filter { it.isNotBlank() }.joinToString(": ") }
                ?: members.joinToString(", ") { it.label }
            Text(line, style = MaterialTheme.typography.bodyMedium, color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        TimeAndBadge(((preview?.createdAt ?: room.updatedAt)).toLong(), unread > 0, use24h)
    }
}

/**
 * Floating "Talk to <chief>": hold to record (same gesture as the composer mic: slide left cancels, up locks), let go
 * to send into the chief's chat; a tap opens that chat. While recording the pill widens into the recording bar.
 */
@Composable
private fun TalkToButton(chief: RoutedBot, avatar: String?, onTap: () -> Unit, onSend: (java.io.File, Long) -> Unit, modifier: Modifier = Modifier) {
    val rig = rememberVoiceNoteRig("home:" + chief.routeKey, onSend)
    val tap by rememberUpdatedState(onTap)
    val callbacks = remember(rig) {
        HoldMicCallbacks(
            onPress = rig.callbacks.onPress,
            onDrag = rig.callbacks.onDrag,
            onLock = rig.callbacks.onLock,
            onRelease = { held, cancelled ->
                // A quick tap opens the chat instead of the "hold to record" hint.
                if (!cancelled && VoiceNotes.tooShort(held)) { rig.cancelRecording(); tap() } else rig.callbacks.onRelease(held, cancelled)
            },
            onTalkBackToggle = rig.callbacks.onTalkBackToggle
        )
    }
    val cancelWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val label = stringResource(R.string.botapp_talk_to, chief.label)
    val dark = MaterialTheme.colorScheme.inverseSurface
    val onDark = MaterialTheme.colorScheme.inverseOnSurface
    Column(modifier.padding(16.dp).navigationBarsPadding(), horizontalAlignment = Alignment.End) {
        rig.micError?.let {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (rig.recording) {
            Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()) {
                RecordingBar(rig.recordingMs ?: 0L, rig.levels, rig.locked, rig.dragX, onCancel = rig.cancelRecording, onSend = rig.finishRecording,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (!(rig.recording && rig.locked)) Surface(
            shape = RoundedCornerShape(40.dp), color = if (rig.recording) MaterialTheme.colorScheme.error else dark, contentColor = if (rig.recording) MaterialTheme.colorScheme.onError else onDark, shadowElevation = 8.dp,
            modifier = Modifier.holdToRecord(true, cancelWidthPx, callbacks, label)
        ) {
            Row(Modifier.padding(start = 10.dp, end = 26.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(chief.profile, size = 52, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape))
                Spacer(Modifier.width(18.dp))
                Icon(Icons.Default.Mic, null, Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
