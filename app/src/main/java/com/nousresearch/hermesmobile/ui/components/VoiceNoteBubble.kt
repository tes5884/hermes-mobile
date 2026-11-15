package com.nousresearch.hermesmobile.ui.components

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.VoiceBubbles
import com.nousresearch.hermesmobile.data.VoiceNotes
import kotlinx.coroutines.*
import java.io.File

/**
 * The inside of a sent voice note's bubble (Nova's 2-chat-voice mock): a dark play button, the waveform and the
 * length; under a hairline the TRANSCRIPT label, the words, and the time. Playing fetches the audio from the gateway.
 */
@Composable
fun VoiceNoteBody(note: VoiceBubbles.Note, timeLabel: String?) {
    val owner = LocalArtifactOwner.current
    val playback = rememberNotePlayback(owner, note.path)
    Column(Modifier.widthIn(min = 220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceNoteStrip(
            seed = note.name,
            seconds = note.seconds,
            playing = playback.playing,
            busy = playback.busy,
            enabled = playback.available,
            progress = VoiceBubbles.progress(playback.positionMs, playback.durationMs),
            positionSec = if (playback.playing || playback.positionMs > 0) playback.positionMs / 1000 else null,
            onToggle = playback.toggle
        )
        if (playback.failed) Text(stringResource(R.string.audio_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .22f))
        Text(
            stringResource(R.string.botapp_transcript), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (note.transcribed) Text(note.transcript, style = MaterialTheme.typography.bodyLarge)
        else Text(stringResource(R.string.botapp_no_transcript), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!timeLabel.isNullOrBlank()) Text(
            timeLabel, Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Play button + bars + length: the same row for a sent note and one still transcribing. */
@Composable
fun VoiceNoteStrip(
    seed: String,
    seconds: Int,
    playing: Boolean,
    busy: Boolean,
    enabled: Boolean,
    progress: Float,
    positionSec: Int?,
    onToggle: () -> Unit
) {
    val label = stringResource(if (playing) R.string.audio_pause else R.string.audio_play)
    val dark = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (enabled) dark else dark.copy(alpha = .35f))
                .clickable(enabled = enabled && !busy, role = Role.Button, onClickLabel = label, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.surface)
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, label, tint = MaterialTheme.colorScheme.surface)
        }
        Spacer(Modifier.width(12.dp))
        val bars = remember(seed) { VoiceBubbles.bars(seed) }
        val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f)
        Canvas(Modifier.weight(1f).height(28.dp)) {
            val step = size.width / bars.size
            val w = (step * .5f).coerceAtLeast(1f)
            bars.forEachIndexed { i, lv ->
                val h = size.height * lv
                drawRoundRect(
                    color = if (VoiceBubbles.played(i, bars.size, progress)) dark else rest,
                    topLeft = Offset(i * step + (step - w) / 2f, (size.height - h) / 2f),
                    size = Size(w, h), cornerRadius = CornerRadius(w / 2f)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            VoiceNotes.clock(positionSec ?: seconds), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Stable
private class NotePlayback {
    var playing by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var positionMs by mutableIntStateOf(0)
    var durationMs by mutableIntStateOf(0)
    var available by mutableStateOf(false)
    var toggle: () -> Unit = {}
}

/** Fetches the note from the gateway on the first tap, then plays/pauses it; pauses when the app leaves the screen. */
@Composable
private fun rememberNotePlayback(owner: ArtifactOwner?, path: String?): NotePlayback {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val state = remember(owner, path) { NotePlayback() }
    state.available = owner != null && path != null
    if (owner == null || path == null) return state
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val attributes = remember { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build() }
    val holder = remember(owner, path) { arrayOfNulls<MediaPlayer>(1) }
    val cached = remember(owner, path) { arrayOfNulls<File>(1) }
    var job by remember(owner, path) { mutableStateOf<Job?>(null) }
    val focus = remember(state) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) { runCatching { holder[0]?.pause() }; state.playing = false } }.build()
    }
    fun pause() { runCatching { holder[0]?.pause() }; state.playing = false; audioManager.abandonAudioFocusRequest(focus) }
    fun play() {
        val p = holder[0] ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (state.positionMs >= state.durationMs) { p.seekTo(0); state.positionMs = 0 }
        p.start(); state.playing = true
    }
    fun release() { holder[0]?.release(); holder[0] = null; cached[0]?.delete(); cached[0] = null }
    fun load() {
        state.busy = true; state.failed = false
        job = scope.launch {
            val file = File.createTempFile("hermes-note-", ".m4a", context.cacheDir)
            var adopted = false
            try {
                withContext(Dispatchers.IO) {
                    if (owner.session.isNotBlank()) owner.repo.rest.sessionMessagesPage(owner.session, owner.profile, limit = 1)
                    val mime = owner.repo.rest.downloadFile(owner.profile, path, owner.session.ifBlank { null }, file, maxBytes = VoiceNotes.MAX_BYTES * 2)
                    require(mime.startsWith("audio/") || mime.substringBefore(';') == "application/octet-stream")
                }
                ensureActive()
                val media = MediaPlayer()
                holder[0] = media; cached[0] = file; adopted = true
                media.setAudioAttributes(attributes)
                media.setDataSource(file.path)
                media.setOnPreparedListener { state.durationMs = it.duration.coerceAtLeast(0); state.busy = false; play() }
                media.setOnCompletionListener { state.playing = false; state.positionMs = state.durationMs; audioManager.abandonAudioFocusRequest(focus) }
                media.setOnErrorListener { _, _, _ -> pause(); release(); state.busy = false; state.failed = true; true }
                media.prepareAsync()
            } catch (e: CancellationException) { state.busy = false; throw e }
            catch (e: Exception) { release(); state.busy = false; state.failed = true }
            finally { if (!adopted) file.delete() }
        }
    }
    state.toggle = { if (state.playing) pause() else if (holder[0] != null) play() else if (!state.busy) load() }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { pause(); job?.cancel() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel(); audioManager.abandonAudioFocusRequest(focus); release() }
    }
    LaunchedEffect(state, state.playing) {
        while (state.playing) { state.positionMs = holder[0]?.currentPosition ?: 0; delay(100) }
    }
    return state
}
