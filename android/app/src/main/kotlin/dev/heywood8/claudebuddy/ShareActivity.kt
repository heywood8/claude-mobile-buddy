package dev.heywood8.claudebuddy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Sends text to the Mac from wherever you found it.
 *
 * The half of the shared clipboard that Android will not let happen by itself. [Clipboard]
 * explains why: only the focused app may read the clipboard, so once the dashboard is off
 * screen there is no automatic path left. The share sheet is the way through — the system hands
 * the text over in the intent, so nothing is read and no restriction applies. Select, share,
 * done, without leaving the app you were in.
 *
 * No window of its own. It exists for the length of one intent.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?.toString()
            .orEmpty()

        val message = when {
            text.isEmpty() -> "Nothing to send"
            !Settings.clipboardEnabled(this) -> "Shared clipboard is off"
            // Deliberately not deferred the way a tapped verdict is. A decision is answering a
            // question the bridge is still holding open, so late is better than never; a clip
            // answers nothing, and one delivered when the link came back would overwrite the
            // Mac's pasteboard minutes later for no reason anybody could reconstruct.
            !Clipboard.share(this, text) -> "Not connected to the bridge"
            text.toByteArray().size > Clip.TEXT_LIMIT ->
                "Sent the first ${Clip.TEXT_LIMIT / 1024} KB"

            else -> "Sent to the Mac"
        }

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
