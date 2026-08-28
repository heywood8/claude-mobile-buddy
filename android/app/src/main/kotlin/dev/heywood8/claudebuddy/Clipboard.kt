package dev.heywood8.claudebuddy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps this phone's clipboard and the Mac's pasteboard holding the same text.
 *
 * Both ends run the same rule, which is what stops the obvious disaster. A clip that arrives
 * from the bridge is put on the clipboard, which wakes the listener in [MainActivity], which
 * would send it back, which would set the Mac's pasteboard, which would wake the watcher over
 * there — a loop with nothing to stop it. So each side remembers the text the two of them last
 * agreed on and refuses to send or apply that exact text again. The loop dies on its first lap.
 *
 * ### Why the two directions do not look alike
 *
 * From Android 10 an app may read the clipboard only while it is the default input method or
 * the app with focus. A foreground service is neither: [BuddyService] can hold a BLE link for
 * hours and still not be allowed to see a single character. `READ_CLIPBOARD_IN_BACKGROUND`
 * exists and is signature-level, so it is not available to anything that is not signed by the
 * platform, and no amount of adb changes that.
 *
 * So the Mac half is genuinely automatic and this half is as close as the platform permits:
 * every clipboard change made while the app is on screen goes over by itself, and everything
 * else reaches the Mac through the share sheet, which hands us the text directly and needs no
 * clipboard read at all. Writing is unrestricted, so a clip *from* the Mac always lands.
 */
object Clipboard {
    /**
     * The text this phone and the Mac last agreed on.
     *
     * Volatile rather than locked: it is written from the service's thread when a clip arrives
     * and read from the main thread when the listener fires, and the only thing a torn read
     * could cost is one redundant clip crossing the link.
     */
    @Volatile
    private var mirror: String? = null

    private val main = Handler(Looper.getMainLooper())

    /** Puts a clip from the bridge on this phone's clipboard. */
    fun apply(context: Context, clip: Clip) {
        if (!Settings.clipboardEnabled(context)) return
        val text = clip.text
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "a clip from the bridge did not decode")
            return
        }
        if (text == mirror) return
        mirror = text

        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        // Posted rather than called here: this arrives on the binder thread the GATT callbacks
        // run on, and the clipboard's own change listeners are dispatched on the main looper.
        // Writing from both places is asking the two to interleave for no gain.
        main.post {
            // Deliberately not marked EXTRA_IS_SENSITIVE. That flag replaces the content of
            // Android's own paste confirmation with "Content hidden", and that confirmation is
            // the only sign this phone gives that a clip arrived at all — a clipboard that
            // changes under you with no notice is worse than one that says so. What would have
            // justified hiding it never gets this far: the Mac refuses to send anything a
            // password manager has marked concealed.
            manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            BuddyState.noteClip(fromPhone = false, chars = text.length)
            Log.i(TAG, "applied ${text.toByteArray().size} bytes from the bridge")
        }
    }

    /**
     * Sends whatever is on the clipboard now, if it is news.
     *
     * Only ever called from a window that has focus — from anywhere else the read comes back
     * null and this does nothing, which is the platform working rather than a bug.
     */
    fun capture(context: Context) {
        if (!Settings.clipboardEnabled(context)) return
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        send(context, text)
    }

    /**
     * Sends text handed to us directly, by the share sheet.
     *
     * The one path that is not subject to the read restriction at all: the system gives us the
     * text in the intent, so there is nothing to read. Returns false when there was nothing
     * holding the link to take it, so the caller can say so rather than appear to have worked.
     */
    fun share(context: Context, text: String): Boolean {
        if (!Settings.clipboardEnabled(context)) return false
        return send(context, text)
    }

    private fun send(context: Context, text: String): Boolean {
        if (text.isEmpty() || text == mirror) return false
        // The mirror means "what the Mac is known to have", so it does not move until
        // something has actually taken the clip. Advancing it before this check would make
        // sharing the same text again — the obvious thing to do after being told the bridge
        // was not connected — look like something already agreed on, and it would never cross.
        val sink = BuddyState.clipSink ?: return false
        mirror = text
        sink.invoke(Clip.of(text, System.currentTimeMillis() / 1000))
        BuddyState.noteClip(fromPhone = true, chars = text.length)
        Log.i(TAG, "sent ${text.toByteArray().size} bytes to the bridge")
        return true
    }

    private const val LABEL = "Claude Buddy"
    private const val TAG = "Clipboard"
}
