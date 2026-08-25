package dev.heywood8.claudebuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every decision, and every request that went unanswered.
 *
 * The unanswered ones are the point. A record of what you approved tells you little on its own;
 * what you never saw is the part worth finding out about afterwards.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val entries by produceState(initialValue = emptyList<Journal.Entry>()) {
        value = Journal.entries(context)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Decisions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Kept for ${Journal.RETENTION_DAYS} days, on this device only.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = onExport, enabled = entries.isNotEmpty()) { Text("Export") }
        }

        if (entries.isEmpty()) {
            Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries) { entry ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "${stamp(entry.at)}  ${label(entry)}  ${entry.tool}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (entry.hint.isNotEmpty()) {
                        Text(entry.hint, style = MaterialTheme.typography.bodySmall)
                    }
                    if (entry.cwd.isNotEmpty()) {
                        Text(entry.cwd, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun label(entry: Journal.Entry): String = when (entry.outcome) {
    "once" -> "allowed"
    "deny" -> "denied"
    else -> "unanswered"
}

private fun stamp(seconds: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
