package com.nousresearch.hermesmobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R
import com.nousresearch.hermesmobile.data.ApprovalCards
import com.nousresearch.hermesmobile.data.BotHome
import com.nousresearch.hermesmobile.ui.components.CollapsibleCommand
import com.nousresearch.hermesmobile.ui.theme.HermesTokens
import com.nousresearch.hermesmobile.ui.theme.hermes
import com.nousresearch.hermesmobile.data.CommandSummary.Kind as SummaryKind

/**
 * One approval card inline in a bot's chat (Nova's mock 3/3b). Pending: "<Bot> needs your OK", the plain-words
 * title, the effect, a Details fold with the exact command and why it asked, then Approve once (filled) / Deny
 * (outline), and "Always allow …" only when Hermes offers it. Answered: one collapsed line with the outcome and time.
 */
@Composable
internal fun InlineApprovalCard(card: ApprovalCards.Card, botName: String, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val pending = card.state == ApprovalCards.State.PENDING
    var details by rememberSaveable(card.requestId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val orange = HermesTokens.Orange
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (pending) orange.copy(alpha = .45f) else MaterialTheme.hermes.line),
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .alpha(if (card.state == ApprovalCards.State.DENIED) .72f else 1f)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (card.state) {
                    ApprovalCards.State.PENDING -> {
                        Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = orange)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.approval_needs_ok, botName), style = MaterialTheme.typography.labelLarge, color = orange, fontWeight = FontWeight.SemiBold)
                    }
                    else -> {
                        val denied = card.state == ApprovalCards.State.DENIED
                        val color = if (denied) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (MaterialTheme.hermes.isDark) HermesTokens.Success else HermesTokens.SuccessLight
                        Icon(if (denied) Icons.Default.Close else Icons.Default.Check, null, Modifier.size(18.dp), tint = color)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(when (card.state) {
                                ApprovalCards.State.ALWAYS -> R.string.approval_done_always
                                ApprovalCards.State.DENIED -> R.string.approval_done_denied
                                else -> R.string.approval_done_once
                            }),
                            style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold
                        )
                        Text(" · " + BotHome.timeLabel(card.resolvedAtMs / 1000, use24h = use24h), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val headline = card.summary?.let { approvalHeadline(it) } ?: card.title
            Text(headline, style = if (pending) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (pending) FontWeight.SemiBold else FontWeight.Medium,
                color = if (card.state == ApprovalCards.State.DENIED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (card.label.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.approval_flagged, card.label), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pending && card.effect.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(card.effect, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (card.command.isNotBlank() || card.reason.isNotBlank()) {
                Row(
                    Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { details = !details }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(if (details) R.string.approval_hide_details else R.string.approval_details),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                    Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (details) {
                    if (card.command.isNotBlank()) CollapsibleCommand(card.command)
                    if (card.reason.isNotBlank()) Text(stringResource(R.string.approval_reason, card.reason), Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pending) {
                Spacer(Modifier.height(12.dp))
                // Neither button is focused by default; Approve once is the filled one, Deny the outline.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAnswer("once") },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface)
                    ) { Text(stringResource(R.string.approval_approve_once), style = MaterialTheme.typography.titleSmall) }
                    OutlinedButton(
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Reject); onAnswer("deny") },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text(stringResource(R.string.approval_deny), style = MaterialTheme.typography.titleSmall) }
                }
                Spacer(Modifier.height(8.dp))
                if (card.allowAlways) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAnswer("always") }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.approval_always), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.approval_always_sub, botName), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                } else {
                    Text(stringResource(R.string.approval_always_asks), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * A command-made headline in plain words (\"Delete the folder “test” in …/ezra/scratch\"). Where the command gives no
 * place, the sentence without one is used.
 */
@Composable
internal fun approvalHeadline(s: com.nousresearch.hermesmobile.data.CommandSummary.Summary): String {
    val subject = s.subject
    val place = s.place
    @Composable fun pick(withPlace: Int, without: Int, arg: String = subject) =
        if (place.isNotBlank()) stringResource(withPlace, arg, place) else stringResource(without, arg)
    @Composable fun named(withName: Int, without: Int) = if (subject.isNotBlank()) stringResource(withName, subject) else stringResource(without)
    return when (s.kind) {
        SummaryKind.DELETE_FOLDER -> pick(R.string.approval_h_delete_folder_in, R.string.approval_h_delete_folder)
        SummaryKind.DELETE_FILE -> pick(R.string.approval_h_delete_file_in, R.string.approval_h_delete_file)
        SummaryKind.DELETE_MANY -> pick(R.string.approval_h_delete_many_in, R.string.approval_h_delete_many)
        SummaryKind.FORCE_PUSH -> if (place.isNotBlank()) stringResource(R.string.approval_h_force_push_to, subject.ifBlank { "…" }, place) else stringResource(R.string.approval_h_force_push)
        SummaryKind.PUSH -> if (place.isNotBlank()) stringResource(R.string.approval_h_push_to, subject.ifBlank { "…" }, place) else stringResource(R.string.approval_h_push)
        SummaryKind.DISCARD_CHANGES -> stringResource(R.string.approval_h_discard_changes)
        SummaryKind.DELETE_UNTRACKED -> stringResource(R.string.approval_h_delete_untracked)
        SummaryKind.RESTART_SERVICE -> named(R.string.approval_h_restart_service, R.string.approval_h_restart_service_any)
        SummaryKind.STOP_SERVICE -> named(R.string.approval_h_stop_service, R.string.approval_h_stop_service_any)
        SummaryKind.START_SERVICE -> named(R.string.approval_h_start_service, R.string.approval_h_start_service_any)
        SummaryKind.DISABLE_SERVICE -> named(R.string.approval_h_disable_service, R.string.approval_h_disable_service_any)
        SummaryKind.RESTART_CONTAINER -> named(R.string.approval_h_restart_container, R.string.approval_h_restart_container_any)
        SummaryKind.STOP_CONTAINER -> named(R.string.approval_h_stop_container, R.string.approval_h_stop_container_any)
        SummaryKind.REMOVE_CONTAINER -> named(R.string.approval_h_remove_container, R.string.approval_h_remove_container_any)
        SummaryKind.STOP_PROCESS -> named(R.string.approval_h_stop_process, R.string.approval_h_stop_process_any)
        SummaryKind.CHANGE_PERMISSIONS -> pick(R.string.approval_h_permissions_in, R.string.approval_h_permissions)
        SummaryKind.CHANGE_OWNER -> pick(R.string.approval_h_owner_in, R.string.approval_h_owner)
        SummaryKind.MOVE -> if (place.isNotBlank()) stringResource(R.string.approval_h_move, subject, place) else stringResource(R.string.approval_h_run, subject)
        SummaryKind.WRITE_DISK -> named(R.string.approval_h_write_disk, R.string.approval_h_write_disk_any)
        SummaryKind.FORMAT_DISK -> named(R.string.approval_h_format_disk, R.string.approval_h_format_disk_any)
        SummaryKind.RUN_REMOTE_SCRIPT -> if (place.isNotBlank()) stringResource(R.string.approval_h_remote_script_from, place) else stringResource(R.string.approval_h_remote_script)
        SummaryKind.EDIT_FILE -> pick(R.string.approval_h_edit_file_in, R.string.approval_h_edit_file)
        SummaryKind.OVERWRITE_FILE -> pick(R.string.approval_h_overwrite_file_in, R.string.approval_h_overwrite_file)
        SummaryKind.REBOOT -> stringResource(R.string.approval_h_reboot)
        SummaryKind.SHUTDOWN -> stringResource(R.string.approval_h_shutdown)
        SummaryKind.RUN -> stringResource(R.string.approval_h_run, subject)
    }
}

/** A row's time for placing answered cards: its message's, or the newest of an activity group's steps; 0 = none. */
internal fun ChatDisplayRow.epochTime(): Long = when (val e = entry) {
    is com.nousresearch.hermesmobile.data.ChatTimelineItem.Message -> e.message.timestamp
    is com.nousresearch.hermesmobile.data.ChatTimelineItem.Activity -> e.steps.maxOfOrNull { it.timestamp } ?: 0L
    null -> 0L
}
