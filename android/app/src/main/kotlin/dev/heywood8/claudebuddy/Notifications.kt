package dev.heywood8.claudebuddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

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
            // Only while there is something to send it to. An action that cannot work is the
            // control that teaches you to stop reading the row — the same argument the
            // dashboard's button row already makes.
            //
            // The switch is read here rather than watched: turning the shared clipboard off
            // leaves this action in the shade until the link next changes, and tapping it then
            // says "Shared clipboard is off" rather than pretending. Honest, and it costs no
            // plumbing from the settings sheet down into the service.
            .apply {
                if (linked && Settings.clipboardEnabled(context)) {
                    addAction(sendClipboard(context))
                }
            }
            .build()

    /**
     * The one gesture that gets text off this phone without leaving the app you are in.
     *
     * Points at an activity rather than a receiver, and that is the entire design: only the
     * focused app may read the clipboard on Android 10 and later, a receiver has no window and
     * would be refused, and an activity has one. See [CaptureActivity].
     */
    private fun sendClipboard(context: Context): Notification.Action {
        val intent = Intent()
            .setClass(context, CaptureActivity::class.java)
            // Its own task, thrown away afterwards: this is launched from the shade while you
            // are somewhere else entirely, and it must not land on top of that app's back stack.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                context, android.R.drawable.ic_menu_upload),
            "Send clipboard",
            pending,
        )
            // Free in the ordinary case — the phone is already unlocked when you have just
            // copied something — and it keeps a locked phone in a pocket from posting whatever
            // was last copied to a workstation on the strength of one blind tap.
            .setAuthenticationRequired(true)
            .build()
    }

    /**
     * Whether a full-screen intent would do anything.
     *
     * Android 14 reserved the grant for calling and alarm apps and made everyone else ask for
     * it on a settings screen of its own. Below that it is an ordinary manifest permission.
     */
    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun approval(
        context: Context,
        prompt: Prompt,
        waiting: Int = 1,
        fullScreen: Boolean = false,
    ): Notification {
        // The command text stays off the lock screen. That a decision is waiting is not
        // sensitive; what it wants to run is.
        return Notification.Builder(context, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_clawd_glyph)
            .setContentTitle("Approve ${prompt.tool}?")
            // Answering one request reveals the next, which without this reads as a button
            // that did nothing.
            .apply { if (waiting > 1) setSubText("1 of $waiting") }
            .setContentText(prompt.hint)
            // why goes above the command, the same order the bubble uses, because it is the
            // shorter read and usually the one that settles the question. Assembled from the
            // parts that are there rather than interpolated: why is empty for every tool that
            // has no description to give, and cwd defaults to empty too — either one written
            // in blind leaves a gap where a line should be.
            .setStyle(
                Notification.BigTextStyle().bigText(
                    listOf(prompt.why, prompt.hint, prompt.cwd)
                        .filter { it.isNotEmpty() }
                        .joinToString("\n\n"),
                ),
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_CALL)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            // Wakes the display and puts the app in front. Deliberately without
            // showWhenLocked on the activity: the screen lights up, the keyguard stays, and
            // what the command actually is remains behind it — the same line the private
            // visibility above draws. Lighting up is a summons, not a disclosure.
            .apply { if (fullScreen) setFullScreenIntent(openApp(context), true) }
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
