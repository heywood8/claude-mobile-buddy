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
 * platform — and measured on this device, `appops` already reports `READ_CLIPBOARD: allow`
 * while the reads are refused anyway, so it is not an app-op gate that could be opened either.
 * `ClipboardService` says why in as many words: *not in focus nor is it a system service*.
 *
 * So the Mac half is genuinely automatic and this half costs one deliberate act, always. The
 * three that exist all end in something holding focus:
 *
 * - the dashboard is on screen, and the clipboard is read as it gains focus;
 * - text arrives through the share sheet, which needs no read at all ([ShareActivity]);
 * - the notification's **Send clipboard** action ([CaptureActivity]), which is the cheapest of
 *   them — it never leaves the app you are in.
 *
 * Writing is unrestricted, so a clip *from* the Mac always lands.
 */
object Clipboard {
    /**
     * What became of an attempt to send.
     *
     * Every case is a different sentence, because all of them are reachable by a person tapping
     * one button and a toast that says the wrong thing about a two-gesture flow is worse than
     * no toast. [ALREADY_THERE] in particular is not a failure — it is what a second tap on the
     * same clipboard means, and calling it an error would read as one.
     */
    enum class Outcome {
        SENT,
        NOTHING,
        ALREADY_THERE,
        NOT_CONNECTED,
        DISABLED;

        val message: String
            get() = when (this) {
                SENT -> "Sent to the Mac"
                NOTHING -> "Nothing to send"
                ALREADY_THERE -> "The Mac already has this"
                NOT_CONNECTED -> "Not connected to the bridge"
                DISABLED -> "Shared clipboard is off"
            }
    }

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
     * Only meaningful from something that holds focus. Called from anywhere else the read comes
     * back null and this reports [Outcome.NOTHING], which is the platform working rather than a
     * bug — but it is also indistinguishable from an empty clipboard, so no caller should be
     * guessing which happened. The three callers that exist all hold focus by construction.
     */
    fun capture(context: Context): Outcome {
        if (!Settings.clipboardEnabled(context)) return Outcome.DISABLED
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return Outcome.NOTHING
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return Outcome.NOTHING
        return send(text)
    }

    /**
     * Sends text handed to us directly, by the share sheet.
     *
     * The one path not subject to the read restriction at all: the system gives us the text in
     * the intent, so there is nothing to read.
     */
    fun share(context: Context, text: String): Outcome {
        if (!Settings.clipboardEnabled(context)) return Outcome.DISABLED
        return send(text)
    }

    private fun send(text: String): Outcome {
        if (text.isEmpty()) return Outcome.NOTHING
        if (text == mirror) return Outcome.ALREADY_THERE

        // The sink being attached says only that the service is up. The BLE session comes and
        // goes underneath it, so the send has to be the thing that reports, not its presence.
        // This was wrong once and the log caught it saying "sent 4095 bytes to the bridge" in
        // the same millisecond as "cannot send a clip: no ready session".
        val sink = BuddyState.clipSink ?: return Outcome.NOT_CONNECTED
        val clip = Clip.of(text, System.currentTimeMillis() / 1000)
        if (!sink.invoke(clip)) return Outcome.NOT_CONNECTED

        // Only now, and to what the *Mac* will hold rather than to what is on this clipboard.
        //
        // Two rules in one line. The mirror does not move until something has actually taken
        // the clip, or sending the same text again — the obvious thing to do after being told
        // the bridge was not connected — would look like something already agreed on and could
        // never cross. And it takes the clamped text, or anything over the limit would arrive
        // truncated, compare unequal when it came back, and overwrite the longer original.
        mirror = clip.text ?: text
        BuddyState.noteClip(fromPhone = true, chars = text.length)
        Log.i(TAG, "sent ${text.toByteArray().size} bytes to the bridge")
        return Outcome.SENT
    }

    private const val LABEL = "Claude Buddy"
    private const val TAG = "Clipboard"
}
