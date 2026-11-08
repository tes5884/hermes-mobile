package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.PendingVoiceNote
import com.nousresearch.hermesmobile.data.VoiceNotes
import kotlin.math.roundToInt

/** What the hold-to-record gesture reports to the composer. */
class HoldMicCallbacks(
    val onPress: () -> Boolean,                 // true = recording started; false = permission asked, ignore this press
    val onDrag: (dx: Float, dy: Float) -> Unit,
    val onLock: () -> Unit,
    val onRelease: (heldMs: Long, cancelled: Boolean) -> Unit,
    val onTalkBackToggle: () -> Unit
)

/**
 * Press and hold to record, release to send. Slide left past a third of the screen cancels; slide up locks (hands-free).
 * TalkBack's activate action toggles record/send instead, since hold gestures don't work with it.
 */
@Composable
fun HoldMicButton(
    enabled: Boolean,
    recording: Boolean,
    cancelWidthPx: Float,
    callbacks: HoldMicCallbacks,
    tooltip: Boolean,
    showLockHint: Boolean,
    modifier: Modifier = Modifier
) {
    val latest by rememberUpdatedState(callbacks)
    val lockPx = with(LocalDensity.current) { 64.dp.toPx() }
    val label = stringResource(R.string.group_voice_note)
    Box(
        modifier.size(44.dp)
            .graphicsLayer { val k = if (recording) 1.3f else 1f; scaleX = k; scaleY = k }
            .semantics { onClick(label) { if (enabled) latest.onTalkBackToggle(); true } }
            .pointerInput(enabled, cancelWidthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val started = latest.onPress()
                    if (!started) {
                        // Permission prompt is up: swallow this press; hold again after allowing.
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    var locked = false
                    var cancelled = false
                    var upAt = down.uptimeMillis
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        upAt = ch.uptimeMillis
                        ch.consume()
                        if (!ch.pressed) break
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        latest.onDrag(dx, dy)
                        if (VoiceNotes.isCancelDrag(dx, cancelWidthPx)) { cancelled = true; break }
                        if (VoiceNotes.isLockDrag(dy, lockPx)) { locked = true; latest.onLock(); break }
                    }
                    if (locked) {
                        // Finger can let go now; wait for it so the lift isn't read as a release.
                        do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    } else {
                        latest.onRelease(upAt - down.uptimeMillis, cancelled)
                        if (cancelled) do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Mic, label, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        if (showLockHint) {
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 2.dp,
                modifier = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true).offset(y = (-64).dp).size(32.dp)
            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) } }
        }
        if (tooltip) {
            Surface(
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 2.dp,
                modifier = Modifier.wrapContentSize(Alignment.TopEnd, unbounded = true).offset(y = (-52).dp)
            ) {
                Text(
                    stringResource(R.string.group_voice_hold_hint), Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1
                )
            }
        }
    }
}

/** The composer while recording: red dot + timer, live waveform, then the slide-to-cancel hint (or Cancel/Send once locked). */
@Composable
fun RecordingBar(
    elapsedMs: Long,
    levels: List<Float>,
    locked: Boolean,
    dragX: Float,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val red = VoiceNotes.nearLimit(elapsedMs)
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
    Row(modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (locked) IconButton(onClick = onCancel) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.group_voice_cancel), tint = MaterialTheme.colorScheme.error) }
        Box(Modifier.padding(start = if (locked) 0.dp else 8.dp).size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(8.dp))
        Text(
            VoiceNotes.clock((elapsedMs / 1000).toInt()), style = MaterialTheme.typography.titleMedium,
            color = if (red) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(32.dp)) {
            val n = 40
            val step = size.width / n
            val barW = step * .55f
            val pad = n - levels.size
            for (i in 0 until n) {
                val lv = if (i < pad) 0f else levels[i - pad]
                // Silence stays a dot; speech grows from the centre line.
                val h = if (lv <= 0f) barW else (size.height * lv).coerceAtLeast(barW)
                drawRoundRect(
                    color = if (lv <= 0f) dim else accent,
                    topLeft = Offset(i * step + (step - barW) / 2f, (size.height - h) / 2f),
                    size = Size(barW, h), cornerRadius = CornerRadius(barW / 2f)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (!locked) {
            val alpha = (1f - (-dragX / 400f)).coerceIn(.3f, 1f)
            Text(
                "‹ " + stringResource(R.string.group_voice_slide_cancel),
                Modifier.offset { IntOffset(dragX.coerceAtMost(0f).roundToInt() / 2, 0) }.graphicsLayer { this.alpha = alpha }.padding(end = 56.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Clip
            )
        } else {
            FilledIconButton(onClick = onSend, modifier = Modifier.size(44.dp)) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.i18n_send)) }
        }
    }
}

/** The user's own voice note before it is in the room: play button + length, then a spinner while it transcribes, or Retry. */
@Composable
fun PendingVoiceBubble(note: PendingVoiceNote, onRetry: () -> Unit, onAudioOnly: () -> Unit, onDiscard: () -> Unit) {
    val maxBubble = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.8f).dp
    val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = shape, modifier = Modifier.widthIn(max = maxBubble).clip(shape)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                LocalNotePlayer(note.filePath, ((note.durationMs + 500) / 1000).toInt())
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!note.failed) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.group_voice_transcribing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            stringResource(R.string.group_voice_failed), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (note.failed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.group_voice_retry)) }
                        TextButton(onClick = onAudioOnly) { Text(stringResource(R.string.group_voice_audio_only)) }
                        IconButton(onClick = onDiscard, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.group_voice_cancel), Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

/** Plays the still-local recording so he can hear it was captured. */
@Composable
private fun LocalNotePlayer(path: String, totalSec: Int) {
    var player by remember(path) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    DisposableEffect(path) { onDispose { player?.release(); player = null } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = {
            val p = player
            if (p != null) { if (playing) { p.pause(); playing = false } else { p.start(); playing = true }; return@FilledTonalIconButton }
            runCatching {
                val mp = android.media.MediaPlayer()
                mp.setDataSource(path)
                mp.setOnCompletionListener { playing = false; it.seekTo(0) }
                mp.prepare(); player = mp; mp.start(); playing = true
            }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.group_voice_play)) }
        Spacer(Modifier.width(8.dp))
        Text(VoiceNotes.clock(totalSec), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
