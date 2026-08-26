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
     */
    fun resume(context: Context, reason: String) {
        if (!Settings.shouldRun(context)) return
        if (Keyring.hosts(context).isEmpty()) {
            Log.i(TAG, "not resuming after $reason: no paired bridges")
            return
        }
        if (!hasPermissions(context)) {
            Log.i(TAG, "not resuming after $reason: bluetooth permissions not granted")
            return
        }
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, BuddyService::class.java)
            )
            Log.i(TAG, "resuming after $reason")
        } catch (e: Exception) {
            // Which foreground service types may start from the background — and from
            // BOOT_COMPLETED in particular — has moved with almost every release, and getting
            // it wrong throws rather than degrading. Failing to come back is survivable; the
            // dashboard's Start button still works. Crashing on boot is not.
            Log.w(TAG, "could not resume after $reason", e)
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
