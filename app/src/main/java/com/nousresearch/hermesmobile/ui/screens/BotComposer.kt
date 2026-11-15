package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.*
import com.nousresearch.hermesmobile.features.VoiceActions
import com.nousresearch.hermesmobile.ui.theme.HermesMono
import com.nousresearch.hermesmobile.ui.theme.hermes
import kotlin.math.roundToInt

/** The bot chat's mic and send button: 60dp, as in Nova's mock. */
private val BigButton = 60.dp

/**
 * A bot's chat composer (Nova's 2-chat-voice / 2b-recording mocks): a round +, a pill field "Message <bot>", and a
 * 60dp dark mic with "Hold to talk" under it. Hold to record (red timer + live bars, slide left to cancel, slide up
 * to lock, let go to send); typed text turns the mic into Send. Model and context sit in the + menu; Stop shows
 * beside the field while a turn runs. Same recorder, gesture and limits as every other chat.
 */
@Composable
internal fun BotComposer(
    botName: String,
    chat: ActiveChat,
    draft: String,
    attachments: List<ComposerAttachment>,
    voice: VoiceActions?,
    rig: VoiceNoteRig,
    holdMic: Boolean,
    cancelWidthPx: Float,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onAttach: () -> Unit,
    onTakePhoto: () -> Unit,
    onModel: () -> Unit,
    onContext: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onSteer: (String) -> Unit,
    onRecordingChange: (Boolean) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var menu by remember(chat.storedSessionId, chat.profile) { mutableStateOf(false) }
    val hasMessage = draft.isNotBlank() || attachments.isNotEmpty()
    val state = VoiceBubbles.composer(rig.recording, rig.locked, hasMessage)
    val reportRecording by rememberUpdatedState(onRecordingChange)
    LaunchedEffect(rig.recording) { reportRecording(rig.recording) }
    DisposableEffect(Unit) { onDispose { reportRecording(false) } }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)) {
        if (voice != null && (voice.recording || voice.busy || voice.speaking || voice.status != null)) DictationStatus(voice)
        rig.micError?.let { Text(it, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        if (!rig.recording) AttachmentStrip(attachments, onRemoveAttachment)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(bottom = 2.dp)) {
                if (rig.recording) BotRecordingPill(rig.recordingMs ?: 0L, rig.levels, rig.locked, rig.dragX, onCancel = rig.cancelRecording)
                else Row(verticalAlignment = Alignment.Bottom) {
                    Box {
                        Surface(
                            onClick = { menu = true }, shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.hermes.lineStrong), modifier = Modifier.size(52.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, stringResource(R.string.chat_more_actions)) } }
                        ComposerMenu(
                            expanded = menu, onDismiss = { menu = false }, chat = chat, draft = draft, attachments = attachments,
                            voice = voice, onDraftChange = onDraftChange, onAttach = onAttach, onTakePhoto = onTakePhoto,
                            onSteer = onSteer, onModel = onModel, onContext = onContext
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.hermes.lineStrong), modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) {
                        TextField(
                            value = draft, onValueChange = onDraftChange, modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    if (chat.running) stringResource(R.string.chat_queue_hint) else stringResource(R.string.botapp_message_bot, botName),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge.let { if (draft.startsWith("/")) it.copy(fontFamily = HermesMono) else it },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default)
                        )
                    }
                    if (chat.running) IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); onStop() }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.StopCircle, stringResource(R.string.chat_stop), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(BigButton), contentAlignment = Alignment.Center) {
                when {
                    voice?.recording == true -> BigRoundButton(Icons.Default.Stop, stringResource(R.string.chat_finish_dictation), onClick = voice.dictate)
                    state == VoiceBubbles.Composer.LOCKED -> BigRoundButton(Icons.AutoMirrored.Filled.Send, stringResource(R.string.i18n_send), onClick = rig.finishRecording)
                    // One call site for idle and recording, so the press that started the recording keeps its gesture.
                    rig.recording || (!hasMessage && holdMic) -> BigHoldMic(rig, cancelWidthPx)
                    else -> BigRoundButton(
                        if (chat.running) Icons.AutoMirrored.Filled.ScheduleSend else Icons.Default.ArrowUpward,
                        stringResource(if (chat.running) R.string.chat_queue else R.string.chat_send),
                        enabled = hasMessage,
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSend(draft) }
                    )
                }
            }
        }
        // Under the mic: what holding does now. Typing keeps the line's height so the field doesn't jump.
        val hint = when (state) {
            VoiceBubbles.Composer.IDLE -> if (holdMic && voice?.recording != true) stringResource(R.string.botapp_hold_to_talk) else null
            VoiceBubbles.Composer.RECORDING -> stringResource(R.string.botapp_release_hint)
            VoiceBubbles.Composer.LOCKED -> stringResource(R.string.botapp_locked_hint)
            VoiceBubbles.Composer.TYPING -> null
        }
        Text(
            hint.orEmpty(), Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
        )
    }
}

/** System dictation (from the + menu) keeps its one-line status above the composer. */
@Composable
private fun DictationStatus(voice: VoiceActions) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            voice.busy -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            voice.recording -> Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            voice.speaking -> com.nousresearch.hermesmobile.ui.components.ThinkingIndicator(dotSize = 4.dp, spacing = 2.dp)
            else -> {}
        }
        Text(
            when {
                voice.recording -> stringResource(R.string.chat_recording)
                voice.busy -> stringResource(R.string.chat_transcribing)
                voice.speaking -> stringResource(R.string.chat_speaking)
                else -> voice.status.orEmpty()
            },
            Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = voice.cancel) { Icon(Icons.Default.Close, stringResource(R.string.chat_dismiss_voice), Modifier.size(18.dp)) }
    }
}

/** A 60dp dark round button (send, send-note, stop dictation). */
@Composable
private fun BigRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .3f),
        contentColor = MaterialTheme.colorScheme.surface, modifier = Modifier.size(BigButton)
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(26.dp)) } }
}

/**
 * The 60dp hold-to-talk mic. While recording it grows inside a soft halo and a lock pill (lock over a chevron)
 * stands above it: sliding up to the pill locks, as in the 2b mock.
 */
@Composable
private fun BigHoldMic(rig: VoiceNoteRig, cancelWidthPx: Float) {
    val label = stringResource(R.string.group_voice_note)
    val dark = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(BigButton), contentAlignment = Alignment.Center) {
        if (rig.recording) Box(
            Modifier.wrapContentSize(unbounded = true).size(BigButton * 1.6f).clip(CircleShape).background(dark.copy(alpha = .08f))
        )
        Box(
            Modifier.size(BigButton)
                .graphicsLayer { val k = if (rig.recording) 1.15f else 1f; scaleX = k; scaleY = k }
                .clip(CircleShape).background(dark)
                .holdToRecord(true, cancelWidthPx, rig.callbacks, label),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Mic, label, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.surface) }
        if (rig.recording && !rig.locked) Surface(
            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp,
            border = BorderStroke(1.dp, MaterialTheme.hermes.lineStrong),
            modifier = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = (-118).dp).width(44.dp).height(84.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Default.LockOpen, stringResource(R.string.botapp_lock), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rig.tooltip) Surface(
            shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 2.dp,
            modifier = Modifier.wrapContentSize(Alignment.TopEnd, unbounded = true).offset(y = (-60).dp)
        ) {
            Text(
                stringResource(R.string.group_voice_hold_hint), Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1
            )
        }
    }
}

/**
 * The pill that replaces + and the field while recording: red dot, timer, red live bars, "‹ Slide to cancel"
 * (follows the finger); once locked, a delete button leads and the hint goes.
 */
@Composable
private fun BotRecordingPill(elapsedMs: Long, levels: List<Float>, locked: Boolean, dragX: Float, onCancel: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.hermes.lineStrong), modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Row(Modifier.fillMaxSize().padding(start = if (locked) 4.dp else 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (locked) IconButton(onClick = onCancel) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.group_voice_cancel), tint = red) }
            Box(Modifier.size(10.dp).clip(CircleShape).background(red))
            Spacer(Modifier.width(10.dp))
            Text(
                VoiceNotes.clock((elapsedMs / 1000).toInt()), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold, color = if (VoiceNotes.nearLimit(elapsedMs)) red else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.weight(1f).height(26.dp)) {
                val n = 24
                val step = size.width / n
                val w = step * .5f
                val pad = n - levels.size.coerceAtMost(n)
                val shown = levels.takeLast(n)
                for (i in 0 until n) {
                    val lv = if (i < pad) 0f else shown[i - pad]
                    val h = if (lv <= 0f) w else (size.height * lv).coerceAtLeast(w)
                    drawRoundRect(
                        color = if (lv <= 0f) dim else red,
                        topLeft = Offset(i * step + (step - w) / 2f, (size.height - h) / 2f),
                        size = Size(w, h), cornerRadius = CornerRadius(w / 2f)
                    )
                }
            }
            if (!locked) {
                Spacer(Modifier.width(8.dp))
                val alpha = (1f - (-dragX / 400f)).coerceIn(.3f, 1f)
                Text(
                    "‹ " + stringResource(R.string.group_voice_slide_cancel),
                    Modifier.offset { IntOffset(dragX.coerceAtMost(0f).roundToInt() / 2, 0) }.graphicsLayer { this.alpha = alpha },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Clip
                )
            }
        }
    }
}
