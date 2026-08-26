package dev.heywood8.claudebuddy

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
 * What is waiting, and the controls for it.
 *
 * Two layouts. Held upright, one column. Turned on its side — which is how a phone propped on
 * a desk sits, and the reason the keep-awake switch exists — the decision keeps the width and
 * the controls move to a rail beside it. In one column on a landscape screen the buttons that
 * answer a request ended up below two settings switches, off the bottom of the display.
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPair: () -> Unit,
    onHistory: () -> Unit,
    onHosts: () -> Unit,
    awake: Boolean,
    onAwakeChange: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // A width breakpoint rather than an orientation check: what matters is whether there
        // is room for two columns, which is also true of a tablet held upright.
        val wide = maxWidth > 600.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PendingDecision()
                    Status()
                    Sessions()
                    RecentCalls()
                }
                Column(
                    Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Controls(onStart, onStop, onPair, onHistory, onHosts, awake, onAwakeChange)
                }
            }
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PendingDecision()
                Status()
                Sessions()
                Controls(onStart, onStop, onPair, onHistory, onHosts, awake, onAwakeChange)
                RecentCalls()
            }
        }
    }
}

@Composable
private fun PendingDecision() {
    val prompt = BuddyState.snapshot?.prompt ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approve ${prompt.tool}?", style = MaterialTheme.typography.titleLarge)
            Text(prompt.hint, style = MaterialTheme.typography.bodyMedium)
            if (prompt.cwd.isNotEmpty()) {
                Text(prompt.cwd, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    BuddyState.answer(prompt.id, Verdict.ONCE, BuddyState.Source.APP)
                }) { Text("Allow") }
                OutlinedButton(onClick = {
                    BuddyState.answer(prompt.id, Verdict.DENY, BuddyState.Source.APP)
                }) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun Status() {
    val context = LocalContext.current
    val paired = remember(BuddyState.running, BuddyState.linked) { Keyring.hosts(context) }
    val snapshot = BuddyState.snapshot

    Text(
        text = when {
            paired.isEmpty() -> "Not paired with any bridge"
            !BuddyState.running -> "Stopped"
            BuddyState.linked -> "Bridge connected"
            else -> "Advertising, waiting for the bridge"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    if (snapshot != null) {
        Text(
            "${snapshot.running} running · ${snapshot.waiting} waiting",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Sessions() {
    val snapshot = BuddyState.snapshot ?: return
    if (snapshot.sessions.isEmpty()) return

    Text("Sessions", style = MaterialTheme.typography.titleSmall)
    for (session in snapshot.sessions) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(
                session.cwd.ifEmpty { session.id.take(8) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(describe(session, snapshot.now), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * How long it has been running, when it last did anything, and how long since you last had a
 * say. The last of those is the one worth reading: a session that has been going for an hour
 * without needing you is fine, and one that has not asked in an hour because it is stuck on
 * something is not, and only the numbers next to each other tell them apart.
 */
private fun describe(session: SessionSummary, now: Long): String {
    val parts = mutableListOf("running ${elapsed(now - session.started)}")
    if (session.active > 0) parts += ago("last call", now - session.active)
    parts += if (session.decided > 0) {
        ago("you decided", now - session.decided)
    } else {
        "you have not stepped in"
    }
    return parts.joinToString(" · ")
}

/** "just now" is already past tense; "just now ago" is not English. */
private fun ago(label: String, seconds: Long): String =
    if (seconds < 60) "$label just now" else "$label ${elapsed(seconds)} ago"

private fun elapsed(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

@Composable
private fun RecentCalls() {
    val entries = BuddyState.snapshot?.entries.orEmpty()
    for (entry in entries) {
        Text(entry, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Controls(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPair: () -> Unit,
    onHistory: () -> Unit,
    onHosts: () -> Unit,
    awake: Boolean,
    onAwakeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val paired = remember(BuddyState.running, BuddyState.linked) { Keyring.hosts(context) }

    if (paired.isEmpty()) {
        // Advertising with an empty keyring can only ever end in unknown_host, so this leads
        // with the one thing that has to happen first.
        Text(
            "Run cmbridge pair on your Mac and scan the code it prints.",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Paired: " + paired.joinToString { it.name.ifEmpty { it.hostId.take(8) } },
                style = MaterialTheme.typography.bodySmall,
            )
            // Rotating a key, adding a second Mac, taking one away: rare, and one of them
            // wants a confirmation dialog. All of it lives a tap further in, out of the way
            // of the buttons actually pressed.
            TextButton(onClick = onHosts) { Text("Manage") }
        }
    }

    // Only what does something right now. A Start button next to a live connection is a
    // control that cannot be pressed meaningfully, and one of those teaches you to stop
    // reading the row.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            paired.isEmpty() -> Button(onClick = onPair) { Text("Pair") }
            BuddyState.running -> OutlinedButton(onClick = onStop) { Text("Stop") }
            else -> Button(onClick = onStart) { Text("Start") }
        }
        OutlinedButton(onClick = onHistory) { Text("History") }
    }

    var notify by remember { mutableStateOf(Settings.notificationsEnabled(context)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = notify,
            onCheckedChange = {
                notify = it
                Settings.setNotificationsEnabled(context, it)
            },
        )
        Text(
            if (notify) "Notify when a decision is waiting" else "Silent — check the app",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    // Read on every recomposition rather than remembered: the grant is given on a settings
    // screen outside this app, and coming back to a switch still insisting it is off would
    // read as the toggle being broken.
    val canFullScreen = Notifications.canUseFullScreen(context)
    var fullScreen by remember { mutableStateOf(Settings.fullScreen(context)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = fullScreen && canFullScreen,
            onCheckedChange = {
                fullScreen = it
                Settings.setFullScreen(context, it)
                if (it && !canFullScreen) {
                    // Android 14 hands this one out on a screen of its own. There is no
                    // runtime prompt to raise, so the best that can be done is to open it.
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }
            },
        )
        Column {
            Text("Light up the screen", style = MaterialTheme.typography.bodyMedium)
            if (fullScreen && !canFullScreen) {
                Text(
                    "Android is holding this one back — grant it in settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (fullScreen) {
                Text(
                    "A request wakes the display. What it wants to run stays behind the lock.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = awake, onCheckedChange = onAwakeChange)
        Column {
            Text("Keep the screen on", style = MaterialTheme.typography.bodyMedium)
            if (awake) {
                // Worth saying out loud rather than leaving to be discovered.
                Text(
                    "The phone will not lock either, and buttons here need no unlock.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
