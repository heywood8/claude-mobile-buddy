package dev.heywood8.claudebuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        BuddyState.answer(id, verdict)
        Notifications.clearApproval(context)
    }

    companion object {
        private const val TAG = "DecisionReceiver"
        const val ACTION_DECIDE = "dev.heywood8.claudebuddy.DECIDE"
        const val EXTRA_ID = "id"
        const val EXTRA_VERDICT = "verdict"
    }
}
