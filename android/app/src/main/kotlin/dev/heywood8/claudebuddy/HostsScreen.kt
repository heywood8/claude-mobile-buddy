package dev.heywood8.claudebuddy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The bridges this phone answers to, and the only place they can be taken away.
 *
 * Forgetting one is a security action rather than a tidy-up: the key being deleted is what
 * authorises approving shell commands on that machine. So it asks first, and it drops the link
 * on the spot instead of letting the current session run to its natural end.
 */
@Composable
fun HostsScreen(
    modifier: Modifier = Modifier,
    onPair: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hosts by remember { mutableStateOf(Keyring.hosts(context)) }
    var forgetting by remember { mutableStateOf<PairedHost?>(null) }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Paired bridges", style = MaterialTheme.typography.titleLarge)

        if (hosts.isEmpty()) {
            Text(
                "None yet. Run cmbridge pair on your Mac and scan the code it prints.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        for (host in hosts) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        host.name.ifEmpty { host.hostId.take(8) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("id ${host.hostId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                    if (BuddyState.linkedHost == host.hostId) {
                        Text("Connected right now", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { forgetting = host }) { Text("Forget") }
                    }
                }
            }
        }

        // Rotation needs no button of its own, and a button implying otherwise would be a lie
        // about where the new key comes from: the bridge makes it, the phone only receives it.
        Text(
            "Scanning a fresh code from a bridge you already have replaces its key instead of " +
                "adding a second entry. That is how a key is rotated — cmbridge pair --rotate " +
                "on the Mac generates it.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onPair) { Text("Scan a code") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }

    val target = forgetting
    if (target != null) {
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${target.name.ifEmpty { target.hostId.take(8) }}?") },
            text = {
                Text(
                    "It cannot reach this phone again until you scan a new pairing code. If it " +
                        "is connected right now, the link drops immediately."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Keyring.remove(context, target.hostId)
                    // Order matters only in that the entry is gone before the link drops: a
                    // reconnect in between would be refused as unknown_host anyway.
                    BuddyState.revoke(target.hostId)
                    hosts = Keyring.hosts(context)
                    forgetting = null
                    if (hosts.isEmpty()) onBack()
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) { Text("Cancel") }
            },
        )
    }
}
