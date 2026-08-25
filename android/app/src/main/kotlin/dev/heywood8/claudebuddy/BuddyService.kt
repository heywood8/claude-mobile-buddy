package dev.heywood8.claudebuddy

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Holds the advertiser and the GATT server for as long as the app is meant to be reachable.
 *
 * Advertising dies with the process the moment Android backgrounds it, so this has to be a
 * foreground service — that is the whole reason it exists.
 */
class BuddyService : Service() {
    private var peripheral: SecurePeripheral? = null
    private var lastPromptId: String? = null
    private var lastSnapshot: Snapshot? = null

    /** What is on screen now, so its disappearance can be recorded if nobody answered it. */
    private var shownPrompt: Prompt? = null
    private val answered = LinkedHashSet<String>()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // Leaving the app with a decision pending has to raise the notification straight away,
        // not at whatever moment the next keepalive happens to land.
        BuddyState.onForegroundChange = { renderApproval() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            Notifications.ID_LINK,
            Notifications.link(this, linked = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val peripheral = SecurePeripheral(
            context = this,
            deviceName = Build.MODEL,
            onSnapshot = ::onSnapshot,
            onReadyChange = ::onLinkChange,
        )
        if (!peripheral.start()) {
            Log.e(TAG, "could not start advertising")
            stopSelf()
            return START_NOT_STICKY
        }
        this.peripheral = peripheral
        BuddyState.sink = { decision, source ->
            peripheral.send(decision)
            journal(decision.id, decision.decision.name.lowercase(), source)
        }
        Journal.prune(this)
        BuddyState.setRunning(true)
        return START_STICKY
    }

    override fun onDestroy() {
        BuddyState.onForegroundChange = null
        BuddyState.sink = null
        BuddyState.setRunning(false)
        peripheral?.stop()
        peripheral = null
        Notifications.clearApproval(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onSnapshot(snapshot: Snapshot) {
        // A request that vanishes without an answer is worth a line of its own. Without it the
        // journal would only show the decisions you made, never the ones that timed out or
        // were withdrawn while you were elsewhere — which is exactly what you would go looking
        // for afterwards.
        val previous = shownPrompt
        if (previous != null && previous.id != snapshot.prompt?.id && previous.id !in answered) {
            journal(previous.id, outcome = "unanswered", source = "", prompt = previous)
        }
        shownPrompt = snapshot.prompt

        lastSnapshot = snapshot
        BuddyState.update(snapshot)
        renderApproval()
    }

    private fun renderApproval() {
        val snapshot = lastSnapshot ?: return
        val manager = getSystemService(NotificationManager::class.java)
        val prompt = snapshot.prompt
        if (prompt == null) {
            lastPromptId = null
            Notifications.clearApproval(this)
            return
        }
        if (!Settings.notificationsEnabled(this)) {
            // Approvals still queue and still wait; they simply stop buzzing.
            Notifications.clearApproval(this)
            return
        }
        if (BuddyState.foreground) {
            // Already on screen. Reset the id so leaving the app buzzes once, rather than
            // treating the request as one you have already been told about.
            lastPromptId = null
            Notifications.clearApproval(this)
            return
        }
        // Keepalives repeat the same prompt every ten seconds. Only a genuinely new request
        // gets to buzz again.
        if (prompt.id != lastPromptId) {
            lastPromptId = prompt.id
            manager.cancel(Notifications.ID_APPROVAL)
        }
        manager.notify(
            Notifications.ID_APPROVAL,
            Notifications.approval(this, prompt, waiting = snapshot.waiting),
        )
    }

    private fun onLinkChange(linked: Boolean) {
        BuddyState.setLinked(linked)
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_LINK, Notifications.link(this, linked))
        if (!linked) {
            lastPromptId = null
            Notifications.clearApproval(this)
        }
    }

    private fun journal(
        id: String,
        outcome: String,
        source: String,
        prompt: Prompt? = lastSnapshot?.prompt?.takeIf { it.id == id } ?: shownPrompt,
    ) {
        if (outcome != "unanswered") {
            answered += id
            // Bounded: this only exists to stop an answered request being logged twice.
            while (answered.size > ANSWERED_MEMORY) answered.remove(answered.first())
        }
        Journal.record(
            this,
            Journal.Entry(
                at = System.currentTimeMillis() / 1000,
                id = id,
                tool = prompt?.tool ?: "?",
                hint = prompt?.hint ?: "",
                cwd = prompt?.cwd ?: "",
                host = peripheral?.host?.name ?: "",
                outcome = outcome,
                source = source,
            ),
        )
    }

    private companion object {
        const val TAG = "BuddyService"
        const val ANSWERED_MEMORY = 64
    }
}
