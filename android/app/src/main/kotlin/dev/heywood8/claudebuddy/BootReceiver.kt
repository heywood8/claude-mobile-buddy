package dev.heywood8.claudebuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts the buddy back after the two events that otherwise take it away silently: a reboot, and
 * the app being replaced by an update.
 *
 * START_STICKY covers a process the system killed, but neither of these is that. A reboot
 * starts nothing, and an update stops the service and leaves it stopped — you find out when a
 * decision you were waiting for appears in the terminal instead of your hand.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                BuddyLauncher.resume(context, intent.action ?: "broadcast")
        }
    }
}
