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
 *
 * A queue does not take the rail away. One request is answerable at a time, its crab rises to
 * the top of the list wearing the accent, and everything else waiting is an edge of paper
 * under the crab that asked.
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
    // The rail answers the head of the queue and only that one. A queue used to send the pair
    // of buttons back to the crabs at their normal size, which is the size the rail exists to
    // avoid — so instead the head is named up here and accented over there.
    val pending = pendingNow()
    val head = pending.firstOrNull()

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
                        Sessions(answering = head?.id)
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
                    if (head != null) Rail(head, behind = pending.size - 1)
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
                    // No rail to belong to, so every stack answers itself.
                    Sessions(answering = null)
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

/**
 * Everything still waiting, head first, minus whatever you just answered.
 *
 * A decision leaves for the bridge immediately; the snapshot that no longer carries it arrives
 * afterwards. In between, the queue still holds a request that is already decided, and a rail
 * offering Allow for something already allowed is the one lie this screen must not tell.
 */
@Composable
private fun pendingNow(): List<Prompt> {
    val answered = BuddyState.lastAnswer?.id
    return BuddyState.snapshot?.pending.orEmpty().filter { it.id != answered }
}

/**
 * The pair of buttons, and the one thing a queue makes them owe you: which crab they answer.
 *
 * Big enough to hit without aiming is the whole point of the rail, so a queue does not get to
 * shrink them or send them away. Which crab they answer is said by the list rather than by a
 * label here: whoever is being answered is at the top of it, wearing the accent. Naming the
 * session instead was wrong — two sessions in the same checkout share a path.
 *
 * Everything else waiting is a layer of paper under its own crab, holding no buttons at all.
 */
@Composable
private fun Rail(head: Prompt, behind: Int) {
    // The queue advances the moment you answer, which puts a fresh request under a thumb that
    // has not left the button yet. Nothing is answerable for a beat after the head changes —
    // the cost is a blink, and what it buys is that no request is ever allowed by momentum.
    var armed by remember(head.id) { mutableStateOf(false) }
    LaunchedEffect(head.id) {
        delay(400)
        armed = true
    }

    // The whole reason for the rail. On a phone lying on a desk you answer this without
    // picking it up, and a 40dp button asks you to aim.
    Button(
        onClick = { BuddyState.answer(head.id, Verdict.ONCE, BuddyState.Source.APP, head.session) },
        enabled = armed,
        modifier = Modifier.fillMaxWidth().height(104.dp),
    ) { Text("Allow", style = MaterialTheme.typography.headlineSmall) }
    OutlinedButton(
        onClick = { BuddyState.answer(head.id, Verdict.DENY, BuddyState.Source.APP, head.session) },
        enabled = armed,
        modifier = Modifier.fillMaxWidth().height(104.dp),
    ) { Text("Deny", style = MaterialTheme.typography.headlineSmall) }

    if (behind > 0) {
        Text(
            if (behind == 1) "1 more behind it." else "$behind more behind it.",
            style = MaterialTheme.typography.bodyMedium,
        )
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
    // That the clipboard moved under you, and which way. Never what it said — this screen gets
    // held up in rooms with other people in it, and the size answers the only question a line
    // here can usefully answer. Timed by this phone: a clip is the one message each end
    // originates, so there is no host frame to work it out in.
    val clip = BuddyState.lastClip
    if (clip != null) {
        Text(
            ago(
                if (clip.fromPhone) "clipboard sent" else "clipboard arrived",
                System.currentTimeMillis() / 1000 - clip.at,
            ) + " · ${clip.chars} characters",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One crab per session, each on its own beat.
 *
 * The count on the line above answers "how many"; this answers "which ones are working", which
 * is the thing you actually scan for. Every crab gets a phase derived from its session id, so
 * a column of them does not blink in unison and stop looking like separate animals.
 *
 * Order comes from [rows]: asking first, in the order they will be answered.
 */
@Composable
private fun Sessions(answering: String?) {
    val snapshot = BuddyState.snapshot ?: return
    val depth = Settings.pathDepth(LocalContext.current)
    val pending = pendingNow()

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

    for (row in rows(snapshot, pending, BuddyState.lastAnswer?.session)) {
        val session = row.session
        val asks = row.asks
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (session != null) {
                ClawdView(
                    // A tap outranks the bridge for a few seconds. Nothing depends on it, which
                    // is the point — this is the one thing on the screen that is just a pet.
                    state = PetMood.forSession(session.id, phoneNow)
                        ?: Pet.sessionState(session, snapshot, BuddyState.lastAnswer, phoneNow),
                    // Fourteen cells across a smaller box put the laptop below one device pixel
                    // a cell, where it stopped being a laptop.
                    modifier = Modifier
                        .width(84.dp)
                        .height(68.dp)
                        .clickable { PetMood.show(PetState.HEART, 4, session.id) },
                    phase = session.id.hashCode(),
                )
            } else {
                ClawdView(
                    if (asks.first().tool == "Bash") PetState.BREAKER else PetState.ATTENTION,
                    Modifier.width(84.dp).height(68.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                if (session != null) {
                    Text(
                        session.cwd.ifEmpty { session.id.take(8) }.let { shortPath(it, depth) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (asks.isNotEmpty()) {
                    // The question belongs to this session, so it is asked here rather than by
                    // a second crab drawn at the top of the screen with nothing else to do.
                    Bubble(asks, roleOf(asks.first(), answering))
                } else if (session != null) {
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
}

/**
 * One line of the list: a session, everything it is asking, and where that puts it.
 *
 * [session] is null for a request whose session the bridge never told us about — one that
 * started before the bridge did and has not called a tool since. Rare, and it must not be drawn
 * anywhere but in the queue order like everything else.
 */
private class SessionRow(
    val session: SessionSummary?,
    val asks: List<Prompt>,
    val rank: Int,
)

/** Not asking. Sorts below everything that is, and keeps the bridge's own order among its kind. */
private const val NOT_ASKING = Int.MAX_VALUE

/**
 * The list in the order the rail will get to it: whoever is being answered on top.
 *
 * This is the whole explanation of which crab those buttons belong to. Naming the session above
 * them cannot do it — two sessions in the same checkout is the ordinary case, and a path is not
 * an identity. Position is unambiguous and needs no words.
 *
 * A session that has just been answered sinks to directly under the queue rather than back to
 * wherever it started. It is the one you were looking at, and having it jump to the far end of
 * the list the moment you decide loses the thread.
 */
private fun rows(
    snapshot: Snapshot,
    pending: List<Prompt>,
    justAnswered: String?,
): List<SessionRow> {
    val queuePosition = HashMap<String, Int>()
    pending.forEachIndexed { index, prompt -> queuePosition.putIfAbsent(prompt.session, index) }
    val answered = justAnswered?.takeIf { it.isNotEmpty() }

    val live = snapshot.sessions.map { session ->
        SessionRow(
            session = session,
            // Every request this session has in the air, not just the first. Parallel tool
            // calls are ordinary, and a request drawn nowhere is a request you cannot answer.
            asks = pending.filter { it.session == session.id },
            rank = queuePosition[session.id]
                ?: if (session.id == answered) pending.size else NOT_ASKING,
        )
    }
    val orphans = pending
        .filter { orphan -> snapshot.sessions.none { it.id == orphan.session } }
        .mapIndexed { index, orphan -> SessionRow(null, listOf(orphan), queuePosition[orphan.session] ?: index) }

    // Stable, so sessions that are not asking stay in the order the bridge sent them and the
    // list does not reshuffle under a thumb on every keepalive.
    return (live + orphans).sortedBy { it.rank }
}

/** How a bubble is drawn, which is entirely a question of where its answer lives. */
private enum class BubbleRole {
    /** No rail on this layout: the bubble carries its own pair of buttons. */
    STANDALONE,

    /** The rail is answering this one, and wears the same colour to say so. */
    ANSWERING,

    /** Waiting its turn. Nothing here is pressable; the rail gets to it next. */
    QUEUED,
}

private fun roleOf(prompt: Prompt, answering: String?): BubbleRole = when {
    answering == null -> BubbleRole.STANDALONE
    prompt.id == answering -> BubbleRole.ANSWERING
    else -> BubbleRole.QUEUED
}

/**
 * What the crab beside it is saying, and how much it still has to say.
 *
 * A stack rather than a card, because one session can have several tool calls in the air at
 * once. The front bubble is the one being answered and every request behind it shows as an
 * edge of paper under it: drawing only the first would hide a request completely, and drawing
 * them all in full would bury the crab under its own queue.
 */
@Composable
private fun Bubble(asks: List<Prompt>, role: BubbleRole) {
    val front = asks.first()
    val behind = asks.size - 1
    // The accent is the whole answer to "which crab do those buttons belong to". It is worth
    // spending a colour on, and it is the only thing on this screen wearing it.
    val bubble = when (role) {
        BubbleRole.ANSWERING -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // Folded by default and unfolded by a tap, remembered per request so the next one does not
    // arrive already open. `clipped` is what the last layout actually did rather than a guess
    // from the text's length — a wrapped line depends on the width, and this screen has two
    // layouts. It is only ever set while folded, so it stays true once open and the tap that
    // folds it back has something to hang on.
    var expanded by remember(front.id) { mutableStateOf(false) }
    var clipped by remember(front.id) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.Top) {
        BubbleTail(bubble, Modifier.padding(top = 10.dp))
        Column(Modifier.weight(1f)) {
            Surface(
                color = bubble,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Nothing to open, nothing to tap. A command that fits is not a control.
                    .then(
                        if (clipped) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Can I run ${front.tool}?", style = MaterialTheme.typography.titleMedium)
                    // What it is for, which is the half of the question the phone used to be
                    // missing: the command says what will happen, this says why. Above the
                    // command rather than below it, because it is the shorter read and the
                    // one that decides most of these on its own.
                    if (front.why.isNotEmpty()) {
                        Text(front.why, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Six lines is enough to recognise a command, and a bubble that fills the
                    // display pushes the buttons off the bottom. A queued one gets fewer still
                    // — it is not the question in front of you yet. But deciding on a command
                    // you can only see the first six lines of is the thing this screen exists
                    // to prevent, so the rest is one tap away rather than absent.
                    Text(
                        front.hint,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else {
                            if (role == BubbleRole.QUEUED) 3 else 6
                        },
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                    )
                    if (clipped) {
                        Text(
                            if (expanded) "Tap to fold" else "Tap to read the rest",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // The bridge cuts the command to 512 bytes before it ever leaves the Mac
                    // and marks the cut with an ellipsis, so "expanded" is not the same as
                    // "all of it". Said only when open, because folded it would be a second
                    // caveat on top of a first. A command genuinely ending in an ellipsis
                    // would say this wrongly; that is cheaper than staying quiet about a
                    // truncation the reader cannot otherwise see.
                    if (expanded && front.hint.endsWith("…")) {
                        Text(
                            "Cut short on the way here — the whole command is in the terminal.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (role == BubbleRole.STANDALONE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                BuddyState.answer(front.id, Verdict.ONCE, BuddyState.Source.APP, front.session)
                            }) { Text("Allow") }
                            OutlinedButton(onClick = {
                                BuddyState.answer(front.id, Verdict.DENY, BuddyState.Source.APP, front.session)
                            }) { Text("Deny") }
                        }
                    }
                }
            }
            // Two edges at most. Past that the stack stops counting and says the number, the
            // way a deck of cards does not get visibly deeper after the first few.
            for (layer in 1..minOf(behind, 2)) {
                Surface(
                    color = bubble.copy(alpha = 0.55f / layer),
                    shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
                    modifier = Modifier
                        .padding(horizontal = (6 * layer).dp)
                        .fillMaxWidth()
                        .height(5.dp),
                ) {}
            }
            if (behind > 2) {
                Text(
                    "$behind more from this session.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
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

    var clipboard by remember { mutableStateOf(Settings.clipboardEnabled(context)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = clipboard,
            onCheckedChange = {
                clipboard = it
                Settings.setClipboardEnabled(context, it)
            },
        )
        Column {
            Text("Share the clipboard", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (clipboard) {
                    // The asymmetry is the platform's, not a setting, so it is stated here
                    // rather than left to be discovered as a bug.
                    "Copying on the Mac lands here. This way needs the app open, or Share."
                } else {
                    "Nothing is read here and nothing arriving is applied."
                },
                style = MaterialTheme.typography.bodySmall,
            )
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
