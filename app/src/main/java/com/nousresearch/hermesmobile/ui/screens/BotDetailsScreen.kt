package com.nousresearch.hermesmobile.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.BotDetails
import com.nousresearch.hermesmobile.data.BotDetailsState
import com.nousresearch.hermesmobile.data.BotHome
import com.nousresearch.hermesmobile.data.CronJob
import com.nousresearch.hermesmobile.data.HermesProfile
import com.nousresearch.hermesmobile.data.KanbanTask
import com.nousresearch.hermesmobile.features.MobileFiles
import com.nousresearch.hermesmobile.ui.components.LocalArtifactOwner
import com.nousresearch.hermesmobile.ui.components.ProfileAvatar
import com.nousresearch.hermesmobile.ui.theme.HermesTokens
import com.nousresearch.hermesmobile.ui.theme.hermes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Bot details can do. Photo, name and model open the existing bot editor. */
internal class BotDetailsActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onOpenFile: (String) -> Unit,
    val onRoutine: (CronJob, Boolean) -> Unit,
    val onAllRoutines: () -> Unit,
    val onOpenBoard: () -> Unit,
    val onQuiet: (Boolean) -> Unit,
    val onStartFresh: () -> Unit
)

private val NeedsYou = HermesTokens.Orange

/**
 * Bot details (Nova's mocks 4 and 4b): back and ⋮, the bot's photo, name, role and live status, then Library, Routines,
 * Cards and Settings on one page with tabs that stay on top and jump to each part; "Start fresh" sits at the bottom.
 * Once the photo scrolls away, the bar shows a small photo and the name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BotDetailsScreen(
    profile: HermesProfile,
    avatar: String?,
    state: BotDetailsState,
    status: BotHome.Status,
    routines: List<CronJob>,
    cards: List<KanbanTask>?,
    quiet: Boolean,
    actions: BotDetailsActions
) {
    BackHandler(onBack = actions.onBack)
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmFresh by remember { mutableStateOf(false) }
    var libraryAll by remember(profile.name) { mutableStateOf(false) }
    val name = profile.label
    // Item indexes: 0 hero, 1 tabs (sticky), then one item per part.
    val parts = buildList {
        add(R.string.botapp_library); add(R.string.botapp_routines)
        if (cards != null) add(R.string.botapp_cards)
        add(R.string.botapp_settings)
    }
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val selected by remember(parts) {
        derivedStateOf { (list.firstVisibleItemIndex - 2).coerceIn(0, parts.lastIndex) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.botapp_back)) }
            if (collapsed) {
                ProfileAvatar(profile, size = 40, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.botapp_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.botapp_edit_bot)) }, onClick = { menu = false; actions.onEdit() })
                }
            }
        }
        if (collapsed) HorizontalDivider(color = MaterialTheme.hermes.line)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero") { Hero(profile, avatar, status, actions.onBack) }
            stickyHeader(key = "tabs") {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            parts.forEachIndexed { i, label ->
                                val on = i == selected
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (on) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
                                    contentColor = if (on) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { list.animateScrollToItem(2 + i) } }
                                ) {
                                    Text(stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                        style = MaterialTheme.typography.titleSmall, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.hermes.line)
                    }
                }
            }
            item(key = "library") { LibrarySection(name, state, libraryAll, { libraryAll = !libraryAll }, actions.onOpenFile) }
            item(key = "routines") { RoutinesPart(routines, actions) }
            if (cards != null) item(key = "cards") { CardsPart(cards, actions.onOpenBoard) }
            item(key = "settings") { SettingsPart(profile, avatar, quiet, actions) }
            item(key = "fresh") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 20.dp).clickable { confirmFresh = true }.padding(vertical = 12.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.botapp_start_fresh, name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.botapp_start_fresh_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f), textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (confirmFresh) AlertDialog(
        onDismissRequest = { confirmFresh = false },
        title = { Text(stringResource(R.string.botapp_start_fresh_title, name)) },
        text = { Text(stringResource(R.string.botapp_start_fresh_body, name)) },
        confirmButton = { TextButton(onClick = { confirmFresh = false; actions.onStartFresh() }) { Text(stringResource(R.string.botapp_start_fresh_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmFresh = false }) { Text(stringResource(R.string.botapp_cancel)) } }
    )
}

@Composable
private fun Hero(profile: HermesProfile, avatar: String?, status: BotHome.Status, onChat: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(profile, size = 128, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape))
        Spacer(Modifier.height(16.dp))
        Text(profile.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        val role = profile.description.substringAfter('|', profile.description).lineSequence().firstOrNull().orEmpty().trim()
        if (role.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(role, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        StatusPillLarge(status, onChat)
        Spacer(Modifier.height(8.dp))
    }
}

/** The live status under the name: orange when the bot needs an OK, blue while it works, grey otherwise. Tap = the chat. */
@Composable
private fun StatusPillLarge(status: BotHome.Status, onChat: () -> Unit) {
    val (bg, fg, text) = when (status.kind) {
        BotHome.Kind.NEEDS_OK -> Triple(MaterialTheme.hermes.orangeTint, NeedsYou,
            listOf(stringResource(R.string.botapp_needs_ok), status.text).filter { it.isNotBlank() }.joinToString(" · "))
        BotHome.Kind.WORKING -> Triple(MaterialTheme.hermes.accentTint, MaterialTheme.colorScheme.primary,
            if (status.text.isBlank()) stringResource(R.string.a21_working) else stringResource(R.string.botapp_working_on, status.text))
        else -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.botapp_idle))
    }
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onChat)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.kind == BotHome.Kind.NEEDS_OK) { Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            else if (status.kind == BotHome.Kind.WORKING) { Box(Modifier.size(8.dp).background(fg, CircleShape)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PartHeader(title: String, link: String? = null, onLink: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (link != null) TextButton(onClick = onLink) { Text(link, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LibrarySection(name: String, state: BotDetailsState, all: Boolean, onToggle: () -> Unit, onOpen: (String) -> Unit) {
    val items = state.library
    val link = when {
        items == null || items.size <= BotDetails.LIBRARY_PREVIEW -> null
        all -> stringResource(R.string.botapp_show_less)
        else -> stringResource(R.string.botapp_see_all_n, items.size)
    }
    PartHeader(stringResource(R.string.botapp_library), link, onToggle)
    when {
        items == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
        state.libraryError != null && items.isEmpty() -> EmptyLine(stringResource(R.string.botapp_library_error, state.libraryError))
        items.isEmpty() -> EmptyLine(stringResource(R.string.botapp_library_empty, name))
        else -> Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (if (all) items else items.take(BotDetails.LIBRARY_PREVIEW)).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item -> LibraryTile(item, Modifier.weight(1f)) { onOpen(item.path) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Decoded thumbnails survive scrolling and reopening the page. */
private object ThumbCache : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

@Composable
private fun LibraryTile(item: BotDetails.LibraryItem, modifier: Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.hermes.line),
        modifier = modifier.aspectRatio(1f).clip(shape).clickable(onClick = onOpen)
    ) {
        if (item.type == BotDetails.FileType.IMAGE) ImageTile(item) else DocTile(item)
    }
}

@Composable
private fun ImageTile(item: BotDetails.LibraryItem) {
    val owner = LocalArtifactOwner.current
    val context = LocalContext.current
    val key = owner?.let { "${it.repo.config.id}:${it.profile}:${item.path}" }
    var bitmap by remember(key) { mutableStateOf(key?.let { ThumbCache.get(it) }) }
    LaunchedEffect(key) {
        if (bitmap != null || owner == null || key == null) return@LaunchedEffect
        bitmap = try {
            withContext(Dispatchers.IO) {
                val file = MobileFiles.target(context, item.path)
                try {
                    val mime = owner.repo.rest.downloadFile(owner.profile, item.path, owner.session.ifBlank { null }, file, maxBytes = 8L * 1024 * 1024)
                    if (mime.startsWith("image/")) com.nousresearch.hermesmobile.data.ImageDecode.file(file.path, 360) else null
                } finally { file.delete() }
            }?.also { ThumbCache.put(key, it) }
        } catch (e: Exception) { if (e is CancellationException) throw e; null }
    }
    Box(Modifier.fillMaxSize()) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.hermes.accentTint.copy(alpha = .5f)))
        Box(Modifier.fillMaxWidth().align(Alignment.BottomStart)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f))))
            .padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(item.name.substringBeforeLast('.'), color = Color.White, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DocTile(item: BotDetails.LibraryItem) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(item.name.substringBeforeLast('.').ifBlank { item.name }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(dayText(item.epochSec), typeText(item.type)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun dayText(epochSec: Long): String = when (BotDetails.day(epochSec)) {
    BotDetails.Day.TODAY -> stringResource(R.string.botapp_today)
    BotDetails.Day.YESTERDAY -> stringResource(R.string.botapp_yesterday)
    BotDetails.Day.OTHER -> BotHome.timeLabel(epochSec)
}

@Composable
private fun typeText(type: BotDetails.FileType): String = stringResource(when (type) {
    BotDetails.FileType.PDF -> R.string.botapp_type_pdf
    BotDetails.FileType.SHEET -> R.string.botapp_type_sheet
    BotDetails.FileType.TEXT -> R.string.botapp_type_text
    BotDetails.FileType.DOC -> R.string.botapp_type_doc
    BotDetails.FileType.SLIDES -> R.string.botapp_type_slides
    BotDetails.FileType.PAGE -> R.string.botapp_type_page
    BotDetails.FileType.IMAGE -> R.string.botapp_type_image
    BotDetails.FileType.AUDIO -> R.string.botapp_type_audio
    BotDetails.FileType.VIDEO -> R.string.botapp_type_video
    BotDetails.FileType.ARCHIVE -> R.string.botapp_type_archive
    BotDetails.FileType.OTHER -> R.string.botapp_type_file
})

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.hermes.line),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
    ) { Column(content = content) }
}

@Composable
private fun RoutinesPart(routines: List<CronJob>, actions: BotDetailsActions) {
    PartHeader(stringResource(R.string.botapp_routines), stringResource(R.string.botapp_see_all), actions.onAllRoutines)
    if (routines.isEmpty()) { EmptyLine(stringResource(R.string.botapp_routines_empty)); return }
    Group {
        routines.take(BotDetails.ROUTINES_PREVIEW).forEachIndexed { i, job ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.hermes.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.name.ifBlank { job.prompt }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(job.scheduleDisplay.ifBlank { job.schedule }, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = job.enabled, onCheckedChange = { on -> actions.onRoutine(job, on) })
            }
        }
    }
}

@Composable
private fun CardsPart(cards: List<KanbanTask>, onOpenBoard: () -> Unit) {
    PartHeader(stringResource(R.string.botapp_board_cards), stringResource(R.string.botapp_open_board), onOpenBoard)
    if (cards.isEmpty()) { EmptyLine(stringResource(R.string.botapp_cards_empty)); return }
    Group {
        cards.take(6).forEachIndexed { i, task ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.hermes.line)
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpenBoard).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val chip = BotDetails.chip(task)
                    val detail = when (chip) {
                        BotDetails.Chip.NEEDS_YOU -> stringResource(R.string.botapp_card_waiting)
                        BotDetails.Chip.WORKING -> if (task.startedAt > 0) stringResource(R.string.botapp_card_started, BotHome.timeLabel(task.startedAt)) else ""
                        BotDetails.Chip.REVIEW -> stringResource(R.string.botapp_card_review)
                        BotDetails.Chip.TODO -> if (task.createdAt > 0) stringResource(R.string.botapp_card_added, BotHome.timeLabel(task.createdAt)) else ""
                    }
                    val line = listOf(task.tenant, detail).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                CardChip(BotDetails.chip(task))
            }
        }
    }
}

@Composable
private fun CardChip(chip: BotDetails.Chip) {
    val (bg, fg, label) = when (chip) {
        BotDetails.Chip.NEEDS_YOU -> Triple(MaterialTheme.hermes.orangeTint, NeedsYou, R.string.botapp_chip_needs_you)
        BotDetails.Chip.WORKING -> Triple(MaterialTheme.hermes.accentTint, MaterialTheme.colorScheme.primary, R.string.botapp_chip_working)
        BotDetails.Chip.REVIEW -> Triple(MaterialTheme.hermes.greenTint, if (MaterialTheme.hermes.isDark) HermesTokens.Success else HermesTokens.SuccessLight, R.string.botapp_chip_review)
        BotDetails.Chip.TODO -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, R.string.botapp_chip_todo)
    }
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(stringResource(label), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun SettingsPart(profile: HermesProfile, avatar: String?, quiet: Boolean, actions: BotDetailsActions) {
    PartHeader(stringResource(R.string.botapp_settings))
    Group {
        SettingRow(onClick = actions.onEdit, leading = { ProfileAvatar(profile, size = 48, avatarDataUrl = avatar, modifier = Modifier.clip(CircleShape)) },
            title = stringResource(R.string.botapp_photo), subtitle = stringResource(R.string.botapp_tap_to_change))
        HorizontalDivider(color = MaterialTheme.hermes.line)
        SettingRow(onClick = actions.onEdit, title = stringResource(R.string.botapp_name), value = profile.label)
        HorizontalDivider(color = MaterialTheme.hermes.line)
        SettingRow(onClick = actions.onEdit, title = stringResource(R.string.botapp_model), subtitle = stringResource(R.string.botapp_model_hint),
            value = profile.model.substringAfterLast('/').ifBlank { stringResource(R.string.botapp_default_model) })
        HorizontalDivider(color = MaterialTheme.hermes.line)
        Row(Modifier.fillMaxWidth().clickable { actions.onQuiet(!quiet) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.botapp_notifications), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.botapp_only_when, profile.label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = quiet, onCheckedChange = actions.onQuiet)
        }
    }
}

@Composable
private fun SettingRow(onClick: () -> Unit, title: String, subtitle: String? = null, value: String? = null, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
