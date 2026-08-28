package dev.heywood8.claudebuddy

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Sends the clipboard to the Mac from the notification shade.
 *
 * The cheapest of the three ways text gets off this phone, and the only one that does not take
 * you out of the app you are in: copy, swipe down, tap, swipe up. It exists because the thing
 * everyone actually wants — the clipboard following you automatically — is not available to any
 * app on Android. Only the focused app, the default keyboard, and platform-signed code may read
 * the clipboard, and a foreground service is none of those. See [Clipboard] for the measurements.
 *
 * **An activity is the loophole, and focus is the whole of it.** A notification action normally
 * fires a broadcast — [DecisionReceiver] does — but a receiver has no window and would be
 * refused exactly like the service. An activity has a window, and a window takes focus.
 *
 * Which is why the read happens in [onWindowFocusChanged] and not in [onCreate]. At `onCreate`
 * the window is not attached yet, nothing has focus, and the read would come back null — the
 * bug this class is one line away from being at all times.
 *
 * No UI. It is a window that exists to be focused, for about as long as that takes.
 */
class CaptureActivity : Activity() {
    /** Focus can be handed back more than once; the clipboard is only sent for the first. */
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val outcome = Clipboard.capture(this)
        // Worth a line of its own: NOTHING here means the read came back empty, and the two
        // reasons for that — an empty clipboard, and a window that never really had focus —
        // are indistinguishable from inside. If this ever starts saying NOTHING for a clipboard
        // that plainly has text in it, the focus assumption above is what broke.
        Log.i(TAG, "capture from the shade: $outcome")
        Toast.makeText(this, outcome.message, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * Leaves nothing behind if focus never arrives.
     *
     * Launching from the shade onto a locked phone puts the keyguard in front of this, and
     * dismissing that keyguard rather than unlocking would otherwise leave an invisible window
     * sitting in a task forever.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing here on purpose. Reading the clipboard at this point returns null: see above.
    }

    private companion object {
        const val TAG = "CaptureActivity"
    }
}
