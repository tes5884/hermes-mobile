package com.nousresearch.hermesmobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermesmobile.R

/** LOCAL (routine-chat): the "clock · Routine · <name>" line that marks a message as written by a scheduled job. */
@Composable
fun RoutinePostHeader(name: String) {
    val word = stringResource(R.string.routine_post)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(5.dp))
        Text(
            if (name.isBlank() || name == word) word else "$word · $name",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** A routine's result inside a bot's own chat: outlined card with the routine header, then the result. */
@Composable
fun RoutinePostCard(name: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            RoutinePostHeader(name)
            Spacer(Modifier.width(0.dp))
            content()
        }
    }
}
