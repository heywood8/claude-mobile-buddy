package dev.heywood8.claudebuddy

import android.content.Context

/** The few things worth remembering between runs that are not secrets. */
object Settings {
    private const val PREFS = "settings"
    private const val NOTIFICATIONS = "notifications"
    private const val KEEP_SCREEN_ON = "keepScreenOn"

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

    /**
     * Whether the dashboard holds the display awake while it is open.
     *
     * Off by default, and the reason is not battery. Preventing the screen from timing out
     * also prevents the device from locking, and the in-app buttons — unlike the notification
     * actions — take no authentication. Left on, the phone sits unlocked on a desk with a
     * one-tap approval for anything Claude Code asks. That is a fine trade when the desk is
     * yours and a poor one otherwise, so it is a choice rather than a default.
     */
    fun keepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEEP_SCREEN_ON, false)

    fun setKeepScreenOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEEP_SCREEN_ON, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
