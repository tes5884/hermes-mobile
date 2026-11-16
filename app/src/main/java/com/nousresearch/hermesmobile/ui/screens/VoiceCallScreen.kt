package com.nousresearch.hermesmobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.CallRouteKind
import com.nousresearch.hermesmobile.data.ChatMessage
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.VoiceCallLoop
import com.nousresearch.hermesmobile.data.VoiceCallUi
import com.nousresearch.hermesmobile.ui.theme.HermesTokens

/** What the call screen can do. Approvals are tap only: nothing here answers one by voice. */
class VoiceCallActions(
    val onMute: (Boolean) -> Unit,
    val onRoute: (CallRouteKind) -> Unit,
    val onHangUp: () -> Unit,
    val onApproval: (requestId: String, choice: String) -> Unit
)

/**
 * A live call with one bot, over its chat: the bot's photo and name, what the call is doing (listening, thinking,
 * speaking, on hold), the last turns of the conversation as live text (they are the chat's own messages, so they
 * stay in the chat after), any approval waiting (answered here by tapping), and Mute, audio route and Hang up.
 * Back hangs up.
 */
@Composable
fun VoiceCallScreen(
    call: VoiceCallUi,
    profile: HermesProfile?,
    avatar: String?,
    messages: List<ChatMessage>,
    streamingText: String,
    approvals: List<ApprovalCards.Card>,
    actions: VoiceCallActions
) {
    BackHandler(onBack = actions.onHangUp)
    val pending = approvals.filter { it.state == ApprovalCards.State.PENDING }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            if (profile != null) {
                com.nousresearch.hermesmobile.ui.components.ProfileAvatar(profile, size = 104, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape))
            }
            Spacer(Modifier.height(12.dp))
            Text(call.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            PhaseLine(call)
            call.notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            LiveTranscript(messages, streamingText, call.label, Modifier.weight(1f).fillMaxWidth())
            if (pending.isNotEmpty()) {
                Text(stringResource(R.string.call_approval_hint), style = MaterialTheme.typography.labelMedium,
                    color = HermesTokens.Orange, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                pending.takeLast(2).forEach { card ->
                    key(card.requestId) { InlineApprovalCard(card, call.label, { choice -> actions.onApproval(card.requestId, choice) }) }
                }
            } else {
                Text(stringResource(R.string.call_stop_hint), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Controls(call, actions)
        }
    }
}

@Composable
private fun PhaseLine(call: VoiceCallUi) {
    val text = when {
        call.muted && call.phase != VoiceCallLoop.Phase.HOLD -> stringResource(R.string.call_muted)
        else -> when (call.phase) {
            VoiceCallLoop.Phase.LISTENING -> stringResource(R.string.call_listening)
            VoiceCallLoop.Phase.CAPTURING -> stringResource(R.string.call_hearing)
            VoiceCallLoop.Phase.TRANSCRIBING -> stringResource(R.string.call_transcribing)
            VoiceCallLoop.Phase.THINKING -> stringResource(R.string.call_thinking)
            VoiceCallLoop.Phase.SPEAKING -> stringResource(R.string.call_speaking)
            VoiceCallLoop.Phase.HOLD -> stringResource(R.string.call_hold)
            VoiceCallLoop.Phase.ENDED -> ""
        }
    }
    val live = call.phase == VoiceCallLoop.Phase.LISTENING || call.phase == VoiceCallLoop.Phase.CAPTURING
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (live && !call.muted) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge,
            color = if (call.phase == VoiceCallLoop.Phase.HOLD) HermesTokens.Orange else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The chat's last turns (what was said and what the bot answers, live), newest at the bottom. */
@Composable
private fun LiveTranscript(messages: List<ChatMessage>, streamingText: String, botName: String, modifier: Modifier) {
    val rows = remember(messages, streamingText) {
        val said = messages.filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() && it.displayKind != "hidden" }
            .takeLast(8).map { (it.role == "user") to it.content.trim() }
        if (streamingText.isNotBlank()) said + (false to streamingText.trim()) else said
    }
    val state = rememberLazyListState()
    LaunchedEffect(rows.size, rows.lastOrNull()?.second?.length) { if (rows.isNotEmpty()) state.scrollToItem(rows.size - 1) }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
        items(rows) { (mine, text) ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                Text(if (mine) stringResource(R.string.push_you) else botName, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(16.dp),
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Controls(call: VoiceCallUi, actions: VoiceCallActions) {
    var routeMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(
            icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
            label = stringResource(if (call.muted) R.string.call_unmute else R.string.call_mute),
            selected = call.muted
        ) { actions.onMute(!call.muted) }
        Box {
            RoundButton(icon = routeIcon(call.route), label = routeLabel(call.route), selected = false, enabled = call.routes.size > 1) { routeMenu = true }
            DropdownMenu(expanded = routeMenu, onDismissRequest = { routeMenu = false }) {
                call.routes.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(routeLabel(kind), fontWeight = if (kind == call.route) FontWeight.SemiBold else null) },
                        leadingIcon = { Icon(routeIcon(kind), null) },
                        onClick = { routeMenu = false; actions.onRoute(kind) }
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledIconButton(onClick = actions.onHangUp, modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD93025), contentColor = Color.White)) {
                Icon(Icons.Filled.CallEnd, stringResource(R.string.call_hang_up), Modifier.size(30.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.call_hang_up), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(60.dp),
            colors = if (selected) IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)
            else IconButtonDefaults.filledTonalIconButtonColors()) {
            Icon(icon, label, Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun routeIcon(kind: CallRouteKind?): ImageVector = when (kind) {
    CallRouteKind.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    CallRouteKind.BLUETOOTH -> Icons.Filled.Bluetooth
    CallRouteKind.WIRED -> Icons.Filled.Headset
    else -> Icons.Filled.PhoneInTalk
}

@Composable
private fun routeLabel(kind: CallRouteKind?): String = stringResource(when (kind) {
    CallRouteKind.SPEAKER -> R.string.call_route_speaker
    CallRouteKind.BLUETOOTH -> R.string.call_route_bluetooth
    CallRouteKind.WIRED -> R.string.call_route_wired
    else -> R.string.call_route_earpiece
})
