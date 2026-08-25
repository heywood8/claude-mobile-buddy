package dev.heywood8.claudebuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var onCameraGranted: (() -> Unit)? = null

    private val linkPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startService(Intent(this, BuddyService::class.java))
    }

    // Export through the document picker rather than a FileProvider: the journal names
    // commands and paths, so where a copy of it lands should be a decision, not a default.
    private val exportJournal = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(Journal.exportText(this).toByteArray())
            }
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onCameraGranted?.invoke()
        onCameraGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sets up the transparent system bars *and* the icon contrast that goes with them, so
        // the clock stays legible whichever way the theme falls.
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = colorScheme(dark)) {
                var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                var awake by remember { mutableStateOf(Settings.keepScreenOn(this)) }
                // Applied to the window rather than held as a wake lock: this only keeps the
                // display alive while our own window is in front, and releases it by itself
                // the moment it is not.
                LaunchedEffect(awake) {
                    if (awake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                Scaffold { padding ->
                    val modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)

                    when (screen) {
                        Screen.PAIRING -> PairingScreen(
                            modifier = modifier,
                            onPaired = { screen = Screen.DASHBOARD },
                            onCancel = { screen = Screen.DASHBOARD },
                        )

                        Screen.HISTORY -> HistoryScreen(
                            modifier = modifier,
                            onExport = { exportJournal.launch("claude-buddy-journal.jsonl") },
                            onBack = { screen = Screen.DASHBOARD },
                        )

                        Screen.DASHBOARD -> Dashboard(
                            modifier = modifier,
                            onStart = ::requestAndStart,
                            onStop = { stopService(Intent(this, BuddyService::class.java)) },
                            onPair = { withCamera { screen = Screen.PAIRING } },
                            onHistory = { screen = Screen.HISTORY },
                            awake = awake,
                            onAwakeChange = {
                                awake = it
                                Settings.setKeepScreenOn(this, it)
                            },
                        )
                    }
                }
            }
        }
    }

    // The notification exists to reach you when you are looking elsewhere. While this window
    // is on screen the card is already in front of you, and a buzz on top of it is noise.
    override fun onStart() {
        super.onStart()
        BuddyState.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        BuddyState.setForeground(false)
    }

    private fun requestAndStart() {
        linkPermissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun withCamera(block: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            block()
        } else {
            onCameraGranted = block
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
}

private enum class Screen { DASHBOARD, PAIRING, HISTORY }

/**
 * Wallpaper-derived colours where the platform offers them, plain Material otherwise.
 *
 * minSdk is 31, which is exactly where dynamic colour arrives, so the fallback is only ever
 * reached on a device that has opted out of it.
 */
@Composable
private fun colorScheme(dark: Boolean) = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

@Composable
private fun Dashboard(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPair: () -> Unit,
    onHistory: () -> Unit,
    awake: Boolean,
    onAwakeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val snapshot = BuddyState.snapshot
    val paired = remember(BuddyState.running, BuddyState.linked) { Keyring.hosts(context) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                paired.isEmpty() -> "Not paired with any bridge"
                !BuddyState.running -> "Stopped"
                BuddyState.linked -> "Bridge connected"
                else -> "Advertising, waiting for the bridge"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        if (paired.isEmpty()) {
            // Advertising with an empty keyring can only ever end in unknown_host, so the
            // dashboard leads with the one thing that has to happen first.
            Text(
                "Run cmbridge pair on your Mac and scan the code it prints.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Paired: " + paired.joinToString { it.name.ifEmpty { it.hostId.take(8) } },
                    style = MaterialTheme.typography.bodySmall,
                )
                // Still reachable — re-pairing after a rotated key, or adding a second Mac —
                // but out of the way of the buttons you actually press.
                TextButton(onClick = onPair) { Text("Re-pair") }
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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

        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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

        val prompt = snapshot?.prompt
        if (prompt != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Approve ${prompt.tool}?", style = MaterialTheme.typography.titleLarge)
                    Text(prompt.hint, style = MaterialTheme.typography.bodyMedium)
                    if (prompt.cwd.isNotEmpty()) {
                        Text(prompt.cwd, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { BuddyState.answer(prompt.id, Verdict.ONCE, BuddyState.Source.APP) }) {
                            Text("Allow")
                        }
                        OutlinedButton(onClick = { BuddyState.answer(prompt.id, Verdict.DENY, BuddyState.Source.APP) }) {
                            Text("Deny")
                        }
                    }
                }
            }
        }

        if (snapshot != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${snapshot.running} running · ${snapshot.waiting} waiting",
                style = MaterialTheme.typography.bodySmall,
            )
            for (entry in snapshot.entries) {
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
