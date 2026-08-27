package dev.heywood8.claudebuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the service back without anyone pressing anything.
 *
 * Being reachable is the whole product, and a buddy that quietly stopped advertising is worse
 * than no buddy at all: every request falls through to the terminal, which looks exactly like
 * the bridge simply having nothing to say. So the app treats "running" as the state to return
 * to, and Stop as the only thing that revokes it.
 */
object BuddyLauncher {
    /**
     * Starts the service if it is supposed to be up.
     *
     * Silent about every reason not to: from a boot receiver there is nobody to tell, and the
     * dashboard already says what is missing when you open it.
     *
     * Returns whether the service was actually asked to start, because the three ways this
     * declines — stopped on purpose, nothing paired, permissions withdrawn — look identical to
     * a successful start from the outside. A caller that is about to tell the user something
     * needs to know which of the two happened; the ones with nobody to tell ignore it.
     */
    fun resume(context: Context, reason: String): Boolean {
        if (!Settings.shouldRun(context)) return false
        if (Keyring.hosts(context).isEmpty()) {
            Log.i(TAG, "not resuming after $reason: no paired bridges")
            return false
        }
        if (!hasPermissions(context)) {
            Log.i(TAG, "not resuming after $reason: bluetooth permissions not granted")
            return false
        }
        return try {
            ContextCompat.startForegroundService(
                context, Intent(context, BuddyService::class.java)
            )
            Log.i(TAG, "resuming after $reason")
            true
        } catch (e: Exception) {
            // Which foreground service types may start from the background — and from
            // BOOT_COMPLETED in particular — has moved with almost every release, and getting
            // it wrong throws rather than degrading. Failing to come back is survivable; the
            // dashboard's Start button still works. Crashing on boot is not.
            Log.w(TAG, "could not resume after $reason", e)
            false
        }
    }

    fun hasPermissions(context: Context): Boolean =
        REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** POST_NOTIFICATIONS is deliberately absent: without it the service still runs. */
    private val REQUIRED = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private const val TAG = "BuddyLauncher"
}
