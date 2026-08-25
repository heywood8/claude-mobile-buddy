package dev.heywood8.claudebuddy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.DASHBOARD) }
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

@Composable
private fun Dashboard(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPair: () -> Unit,
    onHistory: () -> Unit,
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
            for (host in paired) {
                Text("Paired: ${host.name.ifEmpty { host.hostId.take(8) }}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = paired.isNotEmpty()) { Text("Start") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
            OutlinedButton(onClick = onPair) { Text("Pair") }
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
