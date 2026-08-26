package dev.heywood8.claudebuddy

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What is waiting, and nothing else that can wait.
 *
 * Two layouts. Held upright, one column. Turned on its side — which is how a phone propped on
 * a desk sits, and the reason the keep-awake switch exists — the request keeps the width and
 * the answer moves to a rail of its own, where the buttons can be big enough to hit without
 * aiming. Everything that is a setting rather than a decision lives behind the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var settingsOpen by remember { mutableStateOf(false) }
    // One pair of buttons can only answer one request. With several waiting they belong beside
    // the crabs asking, where it is obvious which is which.
    val pending = BuddyState.snapshot?.pending.orEmpty()
    val alone = pending.singleOrNull()

    HandlingEffects()
    LevelUpEffect()

    BoxWithConstraints(modifier) {
        // A width breakpoint rather than an orientation check: what matters is whether there
        // is room for two columns, which is also true of a tablet held upright.
        val wide = maxWidth > 600.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // The sessions scroll; the status is pinned to the floor under them. It is
                // what you read when nothing is happening, and it should be in the same place
                // every time rather than wherever the last crab happened to end.
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Sessions(withButtons = alone == null)
                    }
                    Status()
                }
                Column(
                    Modifier.width(240.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        MenuButton { settingsOpen = true }
                    }
                    if (alone != null) {
                        // The whole reason for the rail. On a phone lying on a desk you answer
                        // this without picking it up, and a 40dp button asks you to aim.
                        Button(
                            onClick = {
                                BuddyState.answer(alone.id, Verdict.ONCE, BuddyState.Source.APP, alone.session)
                            },
                            modifier = Modifier.fillMaxWidth().height(104.dp),
                        ) { Text("Allow", style = MaterialTheme.typography.headlineSmall) }
                        OutlinedButton(
                            onClick = {
                                BuddyState.answer(alone.id, Verdict.DENY, BuddyState.Source.APP, alone.session)
                            },
                            modifier = Modifier.fillMaxWidth().height(104.dp),
                        ) { Text("Deny", style = MaterialTheme.typography.headlineSmall) }
                    } else if (pending.size > 1) {
                        Text(
                            "${pending.size} waiting — answer each one beside its crab.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MenuButton { settingsOpen = true }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Sessions(withButtons = true)
                }
                Status()
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(onDismissRequest = { settingsOpen = false }) {
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Controls(
                    onStart = { settingsOpen = false; onStart() },
                    onStop = { settingsOpen = false; onStop() },
                    onPair = { settingsOpen = false; onPair() },
                    onHistory = { settingsOpen = false; onHistory() },
                    onHosts = { settingsOpen = false; onHosts() },
                    awake = awake,
                    onAwakeChange = onAwakeChange,
                )
            }
        }
    }
}

/**
 * Every crab celebrates when the level goes up.
 *
 * The level is remembered across restarts, so reopening the app is not a party. It only ever
 * moves forward here: a bridge restarted mid-day reports fewer tokens than it did an hour ago
 * — the count is per transcript and per session — and a level that fell would celebrate its
 * way back up the same ground tomorrow.
 */
@Composable
private fun LevelUpEffect() {
    val context = LocalContext.current
    val level = Pet.level(BuddyState.snapshot?.tokens ?: 0)
    LaunchedEffect(level) {
        if (level > Settings.lastLevel(context)) {
            Settings.setLastLevel(context, level)
            PetMood.show(PetState.CELEBRATE, 5)
        }
    }
}

/** Three bars, drawn rather than imported: everything else here is already rectangles. */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Canvas(Modifier.size(22.dp)) {
            val thickness = 2.dp.toPx()
            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                drawRect(
                    color = color,
                    topLeft = Offset(0f, size.height * fraction - thickness / 2f),
                    size = Size(size.width, thickness),
                )
            }
        }
    }
}

/** Stepped, not tapered: a smooth triangle next to a thing made of squares looks borrowed. */
@Composable
private fun BubbleTail(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.width(12.dp).height(24.dp)) {
        val step = 6.dp.toPx()
        drawRect(color, Offset(size.width - step, 0f), Size(step, step * 3))
        drawRect(color, Offset(size.width - step * 2, step), Size(step, step))
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
        val line = buildString {
            append("${snapshot.running} running · ${snapshot.waiting} waiting")
            // Absent rather than zero when the transcript could not be read: a confident
            // "0 tokens" next to a working session would be a lie about the wrong thing.
            if (snapshot.tokensToday > 0) append(" · ${tokens(snapshot.tokensToday)} today")
            // The level lived under the pet that used to sit here; the crabs are the sessions
            // now, and a level belongs to all of them at once.
            val level = Pet.level(snapshot.tokens)
            if (level > 0) append(" · lvl $level")
        }
        Text(line, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One crab per session, each on its own beat.
 *
 * The count on the line above answers "how many"; this answers "which ones are working", which
 * is the thing you actually scan for. Every crab gets a phase derived from its session id, so
 * a column of them does not blink in unison and stop looking like separate animals.
 */
@Composable
private fun Sessions(withButtons: Boolean) {
    val snapshot = BuddyState.snapshot ?: return
    val depth = Settings.pathDepth(LocalContext.current)
    val pending = snapshot.pending

    // A tick so moods expire on their own. The bridge speaks every ten seconds; a heart lasts
    // four, and waiting for the next snapshot to take it away would be most of its life.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val phoneNow = System.currentTimeMillis() / 1000

    for (session in snapshot.sessions) {
        val asking = pending.firstOrNull { it.session == session.id }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClawdView(
                // A tap outranks the bridge for a few seconds. Nothing depends on it, which
                // is the point — this is the one thing on the screen that is just a pet.
                state = PetMood.forSession(session.id, phoneNow)
                    ?: Pet.sessionState(session, snapshot, BuddyState.lastAnswer, phoneNow),
                // Fourteen cells across a smaller box put the laptop below one device pixel a
                // cell, where it stopped being a laptop.
                modifier = Modifier
                    .width(84.dp)
                    .height(68.dp)
                    .clickable { PetMood.show(PetState.HEART, 4, session.id) },
                phase = session.id.hashCode(),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    session.cwd.ifEmpty { session.id.take(8) }.let { shortPath(it, depth) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (asking != null) {
                    // The question belongs to this session, so it is asked here rather than by
                    // a second crab drawn at the top of the screen with nothing else to do.
                    Bubble(asking, withButtons)
                } else {
                    // What you asked for, above what it has been doing about it. An hour
                    // later this is the line that says whether an approval makes sense.
                    if (session.task.isNotEmpty()) {
                        Text(session.task, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        describe(session, snapshot.now),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    // Requests whose sessions the bridge never told us about — sessions that started before it
    // did and have not called a tool since. Rare, and they must not swallow the one thing on
    // this screen that cannot wait.
    for (orphan in pending.filter { orphan -> snapshot.sessions.none { it.id == orphan.session } }) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClawdView(
                if (orphan.tool == "Bash") PetState.BREAKER else PetState.ATTENTION,
                Modifier.width(84.dp).height(68.dp),
            )
            Column(Modifier.weight(1f)) { Bubble(orphan, withButtons) }
        }
    }
}

/** What the crab beside it is saying. */
@Composable
private fun Bubble(prompt: Prompt, withButtons: Boolean) {
    val bubble = MaterialTheme.colorScheme.surfaceVariant
    Row(verticalAlignment = Alignment.Top) {
        BubbleTail(bubble, Modifier.padding(top = 10.dp))
        Surface(color = bubble, shape = RoundedCornerShape(6.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Can I run ${prompt.tool}?", style = MaterialTheme.typography.titleMedium)
                // The bridge already trims this to 512 bytes, which is still a screenful of
                // shell. Six lines is enough to recognise a command; the rest is in the
                // terminal that wrote it, and a bubble that fills the display pushes the
                // buttons off the bottom.
                Text(
                    prompt.hint,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (withButtons) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            BuddyState.answer(prompt.id, Verdict.ONCE, BuddyState.Source.APP, prompt.session)
                        }) { Text("Allow") }
                        OutlinedButton(onClick = {
                            BuddyState.answer(prompt.id, Verdict.DENY, BuddyState.Source.APP, prompt.session)
                        }) { Text("Deny") }
                    }
                }
            }
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
    if (session.tokens > 0) parts += "${tokens(session.tokens)} tokens"
    return parts.joinToString(" · ")
}

/**
 * The last [depth] directories of a path, or all of it when there are no more than that.
 *
 * Trimming a path that was already short would turn `~/work` into `work` and lose the one
 * character saying where it is; there is nothing to gain and a tilde to lose.
 */
fun shortPath(path: String, depth: Int): String {
    if (depth <= 0) return path
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() && it != "~" }
    return if (parts.size <= depth) path else parts.takeLast(depth).joinToString("/")
}

/** "just now" is already past tense; "just now ago" is not English. */
private fun ago(label: String, seconds: Long): String =
    if (seconds < 60) "$label just now" else "$label ${elapsed(seconds)} ago"

/** Two significant figures is all a glance can use, and all the number deserves. */
private fun tokens(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> "${count / 1_000}k"
    else -> count.toString()
}

private fun elapsed(seconds: Long): String = when {
    seconds < 60 -> "under a minute"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
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

    var depth by remember { mutableIntStateOf(Settings.pathDepth(context)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Path", style = MaterialTheme.typography.bodyMedium)
        // The leading half of a path is the same for every checkout you own and spends the
        // width saying so. Zero is here for the rare case where it is not.
        for (option in listOf(1, 2, 3, 0)) {
            val label = if (option == 0) "all" else option.toString()
            if (option == depth) {
                Button(onClick = {}, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(label)
                }
            } else {
                OutlinedButton(
                    onClick = {
                        depth = option
                        Settings.setPathDepth(context, option)
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(label) }
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
