package dev.heywood8.claudebuddy

import android.content.Context

/** The few things worth remembering between runs that are not secrets. */
object Settings {
    private const val PREFS = "settings"
    private const val NOTIFICATIONS = "notifications"
    private const val KEEP_SCREEN_ON = "keepScreenOn"
    private const val SHOULD_RUN = "shouldRun"
    private const val FULL_SCREEN = "fullScreen"
    private const val PATH_DEPTH = "pathDepth"
    private const val LAST_LEVEL = "lastLevel"
    private const val CLIPBOARD = "clipboard"

    /**
     * Whether the clipboard is shared with the Mac.
     *
     * On by default, in both directions: a shared clipboard that has to be armed first is a
     * send button with extra steps, and you reach for it at the moment you have already
     * copied something.
     *
     * Off stops this end completely — nothing is read, nothing that arrives is applied. The
     * Mac is not told, and goes on sending clips into a link that drops them; the bridge's own
     * `--no-clipboard` is the switch for that end. Two switches rather than one negotiated
     * setting, because either device should be able to opt out without the other's agreement.
     */
    fun clipboardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CLIPBOARD, true)

    fun setClipboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CLIPBOARD, enabled).apply()
    }

    /** The highest level already celebrated, so opening the app is not a party. */
    fun lastLevel(context: Context): Int = prefs(context).getInt(LAST_LEVEL, 0)

    fun setLastLevel(context: Context, level: Int) {
        prefs(context).edit().putInt(LAST_LEVEL, level).apply()
    }

    /**
     * How many trailing directories of a working directory to show.
     *
     * Two by default: `~/git/sec/scm` becomes `sec/scm`, which is what tells two sessions apart
     * on a screen this narrow. The leading part is the same for every checkout you own and
     * spends width saying so. Zero means the whole path.
     */
    fun pathDepth(context: Context): Int = prefs(context).getInt(PATH_DEPTH, 2)

    fun setPathDepth(context: Context, depth: Int) {
        prefs(context).edit().putInt(PATH_DEPTH, depth).apply()
    }

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

    /**
     * Whether the buddy is meant to be up.
     *
     * Cleared only by Stop. Everything else that takes the service down — a reboot, an
     * update, the system reclaiming the process — is not a decision you made and is not
     * remembered as one.
     *
     * Default true, so being up is the resting state and Stop is the exception. On a phone
     * with nothing paired and no permissions granted this changes nothing: resuming checks
     * both before it starts anything.
     */
    fun shouldRun(context: Context): Boolean =
        prefs(context).getBoolean(SHOULD_RUN, true)

    fun setShouldRun(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(SHOULD_RUN, value).apply()
    }

    /**
     * Whether a waiting decision takes over the screen instead of arriving as a heads-up.
     *
     * Off by default. It is the right setting for a phone propped on a desk and the wrong one
     * for a phone in a pocket, and only you know which this is.
     */
    fun fullScreen(context: Context): Boolean =
        prefs(context).getBoolean(FULL_SCREEN, false)

    fun setFullScreen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(FULL_SCREEN, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
