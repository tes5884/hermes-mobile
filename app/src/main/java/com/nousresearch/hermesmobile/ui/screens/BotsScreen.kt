@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nousresearch.hermesmobile.ui.screens

import com.nousresearch.hermesmobile.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nousresearch.hermesmobile.data.BotProfileConfig
import com.nousresearch.hermesmobile.data.*
import com.nousresearch.hermesmobile.ui.components.*
import com.nousresearch.hermesmobile.ui.theme.hermes

/**
 * The start page (bot-first, Nova's mock 2026-11-15): [BotHomeContent] lists the bots and channels; an open room or
 * the channels list takes the page over. Roster actions (pin, Edit bot, Delete bot) sit on each row's long press;
 * "New side chat" is gone on purpose: a bot is one continuous chat.
 */
@Composable
fun BotsScreen(
    onMenu: () -> Unit,
    profiles: List<HermesProfile>,
    routedBots: List<RoutedBot>,
    botRosterLoading: Boolean,
    editor: BotProfileConfig?,
    editorLoadingProfile: String?,
    editorSaving: Boolean,
    groupCapabilities: GroupCapabilities?,
    groupRooms: List<GroupRoom>,
    activeGroup: ActiveGroupRoom?,
    groupsLoading: Boolean,
    groupActionBusy: Boolean,
    groupError: String?,
    avatarAssets: Map<String, String>,
    sessionDots: Map<String, com.nousresearch.hermesmobile.data.SessionDot>,
    onEnsureAvatar: (HermesProfile) -> Unit,
    onSwitchConnection: (String) -> Unit,
    onOpen: (HermesProfile) -> Unit,
    onCreate: (String, String, String, String?) -> Unit,
    onTalk: (HermesProfile, java.io.File, Long) -> Unit,
    onEdit: (HermesProfile) -> Unit,
    onSaveEditor: (BotProfileConfig) -> Unit,
    onCloseEditor: () -> Unit,
    onDelete: (HermesProfile) -> Unit,
    onSetPinned: (HermesProfile, Boolean) -> Unit,
    onRefreshGroups: () -> Unit,
    onCreateGroup: (String, Set<String>) -> Unit,
    onOpenGroup: (GroupRoom) -> Unit,
    onCloseGroup: () -> Unit,
    channelsOpen: Boolean,
    onShowChannels: (Boolean) -> Unit,
    onSendGroup: (String) -> Unit,
    onReplyGroupThread: (String?) -> Unit,
    onRenameGroup: (String) -> Unit,
    onStopGroup: () -> Unit,
    onRetryGroup: (GroupPendingAction) -> Unit,
    onApproveGroup: (GroupPendingAction, String) -> Unit,
    onDisbandGroup: () -> Unit,
    groupUnread: Map<String, Int> = emptyMap(),
    botUnread: Set<String> = emptySet(),
    groupPreviews: Map<String, RoomPreview> = emptyMap()
) {
    var create by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<HermesProfile?>(null) }
    // "All channels" / "New channel" from home: the rooms list with its create button, until back. Held by the view
    // model, so opening it is a navigation like any other (a waiting "Talk to …" note is dropped, an open in flight superseded).
    val channels = channelsOpen
    val allBots = remember(routedBots) { routedBots.filter { it.profile.isBot } }

    if (activeGroup != null) {
        GroupsPanel(
            profiles, routedBots, groupCapabilities, groupRooms, activeGroup, groupsLoading, groupActionBusy, groupError,
            avatarAssets, onEnsureAvatar, onRefreshGroups, onCreateGroup, onOpenGroup, onCloseGroup,
            onSendGroup, onReplyGroupThread, onRenameGroup, onStopGroup, onRetryGroup, onApproveGroup, onDisbandGroup
        )
        return
    }

    if (channels) {
        androidx.activity.compose.BackHandler { onShowChannels(false) }
        Column(Modifier.fillMaxSize()) {
            HermesTopBar(
                stringResource(R.string.botapp_channels),
                leading = { IconButton(onClick = { onShowChannels(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.botapp_back_to_bots)) } }
            )
            DashedDivider(color = MaterialTheme.hermes.line)
            GroupsPanel(
                profiles, routedBots, groupCapabilities, groupRooms, null, groupsLoading, groupActionBusy, groupError,
                avatarAssets, onEnsureAvatar, onRefreshGroups, onCreateGroup, onOpenGroup, onCloseGroup,
                onSendGroup, onReplyGroupThread, onRenameGroup, onStopGroup, onRetryGroup, onApproveGroup, onDisbandGroup, groupUnread, groupPreviews
            )
        }
    } else if (allBots.isEmpty() && !botRosterLoading) {
        Column(Modifier.fillMaxSize()) {
            HermesTopBar(stringResource(R.string.botapp_title), leading = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, stringResource(R.string.a21_menu)) } })
            EmptyState(Icons.Default.SmartToy, stringResource(R.string.i18n_bots_empty), stringResource(R.string.i18n_bots_empty_hint)) {
                Button(onClick = { create = true }) { Text(stringResource(R.string.ui_b23572a604)) }
            }
        }
    } else {
        BotHomeContent(
            bots = allBots,
            sessionDots = sessionDots,
            botUnread = botUnread,
            avatarAssets = avatarAssets,
            rooms = groupRooms,
            roomUnread = groupUnread,
            roomPreviews = groupPreviews,
            loading = botRosterLoading,
            actions = BotHomeActions(
                onMenu = onMenu,
                onOpen = onOpen,
                onSwitchConnection = onSwitchConnection,
                onEdit = onEdit,
                onDelete = { deleteCandidate = it },
                onTogglePin = { onSetPinned(it, !it.botPinned) },
                onCreateBot = { create = true },
                onOpenChannel = onOpenGroup,
                onAllChannels = { onShowChannels(true) },
                onTalk = onTalk,
                onEnsureAvatar = onEnsureAvatar
            ),
            sectionOf = ::botSection
        )
    }

    if (create) {
        CreateBotDialog(
            profiles = profiles,
            onDismiss = { create = false },
            onCreate = { name, title, description, clone ->
                create = false
                onCreate(name, title, description, clone)
            }
        )
    }

    editor?.let {
        BotEditorDialog(
            config = it,
            saving = editorSaving,
            onDismiss = onCloseEditor,
            onSave = onSaveEditor,
            sectionOptions = remember(allBots) { allBots.map { botSection(it) }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() } }
        )
    }

    if (editorLoadingProfile != null) {
        Dialog(onDismissRequest = onCloseEditor) {
            Surface(shape = MaterialTheme.shapes.large) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.i18n_bots_loading_config, editorLoadingProfile))
                }
            }
        }
    }

    deleteCandidate?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.i18n_delete_named, profile.label)) },
            text = {
                Text(
                    stringResource(R.string.i18n_bots_delete_body)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !profile.isDefault,
                    onClick = {
                        deleteCandidate = null
                        onDelete(profile)
                    }
                ) { Text(stringResource(R.string.ui_e2d0a54968)) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.ui_19766ed6cc)) } }
        )
    }
}

@Composable
private fun CreateBotDialog(
    profiles: List<HermesProfile>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var clone by remember { mutableStateOf<String?>("default") }
    val slug = name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
    val valid = Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches(slug) && profiles.none { it.name == slug }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_b23572a604)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HermesTextField(name, { name = it }, stringResource(R.string.i18n_prof_name), placeholder = "researcher")
                if (name.isNotBlank() && !valid) {
                    Text(
                        stringResource(if (profiles.any { it.name == slug }) R.string.i18n_bots_exists else R.string.i18n_bots_name_rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HermesTextField(title, { title = it }, stringResource(R.string.i18n_display_name), placeholder = "Researcher")
                HermesTextField(description, { description = it }, stringResource(R.string.i18n_description), singleLine = false)
                Text(stringResource(R.string.ui_6e31e77621), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(clone == null, { clone = null })
                    Text(stringResource(R.string.ui_e0367b3cc8))
                }
                profiles.take(8).forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(clone == profile.name, { clone = profile.name })
                        Text(profile.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(slug, title.trim(), description.trim(), clone) }) {
                Text(stringResource(R.string.ui_4759498ac2))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_19766ed6cc)) } }
    )
}

/** Names the roster was filed under before the Section field existed, so a fresh install shows the departments. */
private val DEFAULT_SECTIONS = mapOf(
    "mason" to "Engineering", "nova" to "Engineering", "otto" to "Engineering", "shomer" to "Engineering", "ward" to "Engineering",
    "kit" to "Engineering",
    "della" to "Front office", "ezra" to "Front office", "lexi" to "Front office", "penny" to "Front office",
    "sloane" to "Front office", "iris" to "Front office",
    "chaim" to "Personal"
)

internal fun botSection(bot: RoutedBot): String =
    bot.profile.botSection.ifBlank { DEFAULT_SECTIONS[bot.profile.name.lowercase()].orEmpty() }

/** Sections alphabetical, unassigned bots under "Other" last; bots alphabetical inside each. */
internal fun botSections(bots: List<RoutedBot>): List<Pair<String, List<RoutedBot>>> {
    // Pinned bots live only in a "Pinned" section above every department (not repeated below), A-Z.
    val (pinned, rest) = bots.partition { it.profile.botPinned }
    val departments = rest.groupBy { botSection(it).ifBlank { "Other" } }
        .map { (name, list) -> name to list.sortedBy { it.label.lowercase() } }
        .sortedWith(compareBy({ it.first == "Other" }, { it.first.lowercase() }))
    return if (pinned.isEmpty()) departments else listOf(PINNED_SECTION to pinned.sortedBy { it.label.lowercase() }) + departments
}

internal const val PINNED_SECTION = "Pinned"
