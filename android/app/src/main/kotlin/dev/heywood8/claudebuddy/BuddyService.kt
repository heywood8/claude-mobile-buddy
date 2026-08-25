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
        BuddyState.sink = peripheral::send
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

    private companion object {
        const val TAG = "BuddyService"
    }
}
