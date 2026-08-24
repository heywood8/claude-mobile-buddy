package dev.heywood8.claudebuddy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Carries a tap on a notification action back to the link. */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val verdict = runCatching {
            Verdict.valueOf(intent.getStringExtra(EXTRA_VERDICT) ?: "")
        }.getOrNull() ?: return
        BuddyState.answer(id, verdict)
        Notifications.clearApproval(context)
    }

    companion object {
        const val ACTION_DECIDE = "dev.heywood8.claudebuddy.DECIDE"
        const val EXTRA_ID = "id"
        const val EXTRA_VERDICT = "verdict"
    }
}
