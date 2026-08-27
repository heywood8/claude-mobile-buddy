package dev.heywood8.claudebuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** Carries a tap on a notification action back to the link. */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val verdict = runCatching {
            Verdict.valueOf(intent.getStringExtra(EXTRA_VERDICT) ?: "")
        }.getOrNull() ?: return
        // Logged because a tap that goes nowhere is otherwise invisible: without this there is
        // no way to tell a notification action that never fired from one whose decision was
        // dropped further down.
        Log.i(TAG, "notification action: $verdict for $id")
        if (BuddyState.answer(id, verdict, BuddyState.Source.NOTIFICATION)) {
            Notifications.clearApproval(context)
            return
        }
        // Nothing is holding the link. Cancelling the card here — which is what used to happen
        // either way — took away the only way to try again, and left a request the bridge is
        // still waiting on with nothing on screen pointing at it. So the card stays, the tap is
        // kept, and the service is asked to come back and deliver it.
        Log.i(TAG, "no link to take $verdict for $id: deferring it and starting the service")
        BuddyState.defer(id, verdict)
        val coming = BuddyLauncher.resume(context, "notification tap")
        Toast.makeText(
            context,
            if (coming) "Reconnecting to the bridge — your answer is on its way"
            else "Not connected to the bridge",
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        private const val TAG = "DecisionReceiver"
        const val ACTION_DECIDE = "dev.heywood8.claudebuddy.DECIDE"
        const val EXTRA_ID = "id"
        const val EXTRA_VERDICT = "verdict"
    }
}
