package dev.heywood8.claudebuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifications {
    const val CHANNEL_LINK = "link"
    const val CHANNEL_APPROVAL = "approval"
    const val ID_LINK = 1
    const val ID_APPROVAL = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LINK,
                "Bridge link",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "The persistent notification while the bridge is reachable" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                "Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Claude Code is waiting for a decision"
                enableVibration(true)
            }
        )
    }

    fun link(context: Context, linked: Boolean): Notification =
        Notification.Builder(context, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (linked) "Bridge connected" else "Waiting for the bridge")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun approval(context: Context, prompt: Prompt): Notification {
        // The command text stays off the lock screen. That a decision is waiting is not
        // sensitive; what it wants to run is.
        return Notification.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Approve ${prompt.tool}?")
            .setContentText(prompt.hint)
            .setStyle(Notification.BigTextStyle().bigText("${prompt.hint}\n\n${prompt.cwd}"))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(action(context, prompt.id, Verdict.ONCE, "Allow"))
            .addAction(action(context, prompt.id, Verdict.DENY, "Deny"))
            .build()
    }

    fun clearApproval(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_APPROVAL)
    }

    private fun action(
        context: Context,
        id: String,
        verdict: Verdict,
        label: String,
    ): Notification.Action {
        // setClass rather than the Intent(Context, Class) constructor, which would set the
        // same component. Spelling the target out keeps the intent visibly explicit to a
        // reader, and to static analysis: CodeQL treats an intent as explicit only when it
        // sees setPackage, setClass, setClassName or setComponent.
        val intent = Intent(DecisionReceiver.ACTION_DECIDE)
            .setClass(context, DecisionReceiver::class.java)
            .putExtra(DecisionReceiver.EXTRA_ID, id)
            .putExtra(DecisionReceiver.EXTRA_VERDICT, verdict.name)
        // FLAG_IMMUTABLE means the notification shade cannot fill anything in. Worth knowing
        // that static analysis cannot see this: CodeQL follows the flag only through a Java
        // bitwise expression, and Kotlin's `or` is a method call, so it assumes the worst.
        val pending = PendingIntent.getBroadcast(
            context,
            (id + verdict.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = if (verdict == Verdict.ONCE) {
            android.R.drawable.ic_menu_send
        } else {
            android.R.drawable.ic_menu_close_clear_cancel
        }
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, icon),
            label,
            pending,
        )
            // Approving arbitrary shell commands from a locked phone in a pocket is an
            // incident waiting to happen, so the device has to be unlocked first.
            .setAuthenticationRequired(true)
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent().setClass(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
