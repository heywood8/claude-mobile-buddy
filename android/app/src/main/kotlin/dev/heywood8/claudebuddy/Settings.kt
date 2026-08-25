package dev.heywood8.claudebuddy

import android.content.Context

/** The few things worth remembering between runs that are not secrets. */
object Settings {
    private const val PREFS = "settings"
    private const val NOTIFICATIONS = "notifications"

    /**
     * Whether a pending approval raises a notification.
     *
     * On by default: the whole point is to reach you when you are looking elsewhere. Off is for
     * when you would rather come to the app yourself — approvals still queue and still wait,
     * they simply stop buzzing.
     */
    fun notificationsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(NOTIFICATIONS, true)

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(NOTIFICATIONS, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
