package dev.heywood8.claudebuddy

import android.Manifest
import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startService(Intent(this, BuddyService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Dashboard(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        onStart = ::requestAndStart,
                        onStop = { stopService(Intent(this, BuddyService::class.java)) },
                    )
                }
            }
        }
    }

    private fun requestAndStart() {
        permissions.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }
}

@Composable
private fun Dashboard(
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val snapshot = BuddyState.snapshot
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = when {
                !BuddyState.running -> "Stopped"
                BuddyState.linked -> "Bridge connected"
                else -> "Advertising, waiting for the bridge"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("Start") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
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
                        Button(onClick = { BuddyState.answer(prompt.id, Verdict.ONCE) }) {
                            Text("Allow")
                        }
                        OutlinedButton(onClick = { BuddyState.answer(prompt.id, Verdict.DENY) }) {
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
