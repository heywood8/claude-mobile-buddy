package dev.heywood8.claudebuddy

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the screen and the notification both read.
 *
 * A process-wide object is not where this belongs long term, but the walking skeleton has
 * exactly one service, one screen and one link, and threading a repository through them
 * proves nothing about whether the BLE path works.
 */
object BuddyState {
    private val main = Handler(Looper.getMainLooper())

    var snapshot by mutableStateOf<Snapshot?>(null)
        private set

    var linked by mutableStateOf(false)
        private set

    /** Which bridge is on the other end, so the one you are about to forget can say so. */
    var linkedHost by mutableStateOf<String?>(null)
        private set

    var lastAnswer by mutableStateOf<Answer?>(null)
        private set

    var running by mutableStateOf(false)
        private set

    /** True while our own window is on screen, so the notification can stay out of the way. */
    var foreground by mutableStateOf(false)
        private set

    /** Set by the service, so backgrounding the app can raise the notification immediately
     *  rather than waiting for the next keepalive. */
    @Volatile
    var onForegroundChange: (() -> Unit)? = null

    /** Set by the service while it holds the link. Second argument is where the tap came from. */
    @Volatile
    var sink: ((Decision, String) -> Unit)? = null

    /**
     * Set by the service while it holds the link.
     *
     * Separate from [sink] because the two have different callers: a decision comes from the
     * screen or the notification, a clip from the share sheet or from whatever had focus when
     * the clipboard moved.
     */
    @Volatile
    var clipSink: ((Clip) -> Unit)? = null

    /**
     * That a clip crossed, and which way. Never what it said.
     *
     * The dashboard is a thing you hold up in a room with other people in it, and the point of
     * the line is that the clipboard changed under you — which the size and the direction
     * answer, and the text would only answer louder.
     */
    var lastClip by mutableStateOf<ClipNote?>(null)
        private set

    /** Set by the service while it holds the link. */
    @Volatile
    var onRevoke: ((String) -> Unit)? = null

    /**
     * A tap taken while nothing was holding the link.
     *
     * Kept rather than discarded because the request it answers is not gone: the bridge waits
     * on it for up to half an hour, which is long enough for the service to come back and hand
     * the verdict over. [BuddyService] delivers it, and only against a snapshot that still
     * lists the request — so a tap can outlive the service without outliving the question.
     */
    @Volatile
    var deferred: DeferredTap? = null
        private set

    fun update(value: Snapshot) = main.post { snapshot = value }

    fun setLinked(value: Boolean) = main.post {
        linked = value
        if (!value) {
            snapshot = null
            linkedHost = null
        }
    }

    fun setLinkedHost(hostId: String?) = main.post { linkedHost = hostId }

    fun setRunning(value: Boolean) = main.post { running = value }

    fun setForeground(value: Boolean) = main.post {
        if (foreground == value) return@post
        foreground = value
        onForegroundChange?.invoke()
    }

    /**
     * Hands a verdict to the link, and says whether anything was there to take it.
     *
     * The answer is only recorded when it actually left: a crab reacting to a decision the
     * bridge never saw is a lie the screen tells about the terminal, and the caller cannot
     * tell one from the other without being told.
     */
    fun answer(id: String, verdict: Verdict, source: String, session: String = ""): Boolean {
        val sink = this.sink ?: return false
        sink.invoke(Decision(id = id, decision = verdict), source)
        val at = System.currentTimeMillis() / 1000
        main.post { lastAnswer = Answer(id, session, verdict, at) }
        return true
    }

    fun noteClip(fromPhone: Boolean, chars: Int) = main.post {
        lastClip = ClipNote(fromPhone, chars, System.currentTimeMillis() / 1000)
    }

    /** [at] is this phone's clock: both ends of a clip are equally ours to time. */
    data class ClipNote(
        val fromPhone: Boolean,
        val chars: Int,
        val at: Long,
    )

    /** Holds a tap that had nowhere to go. A newer one replaces it: the head of the queue moved. */
    fun defer(id: String, verdict: Verdict) {
        deferred = DeferredTap(id, verdict, System.currentTimeMillis() / 1000)
    }

    fun clearDeferred() {
        deferred = null
    }

    /**
     * Your last decision, so the crab that asked can react to it.
     *
     * Timed by this phone's clock, and carrying the session because the request it belonged to
     * is gone from the next snapshot — answering it is what removed it. The session is empty
     * when the answer came from the notification, which knows an id and nothing else.
     */
    data class Answer(
        val id: String,
        val session: String,
        val verdict: Verdict,
        val at: Long,
    )

    /** [at] is this phone's clock, and is only ever used to say how stale a delivered tap was. */
    data class DeferredTap(
        val id: String,
        val verdict: Verdict,
        val at: Long,
    )

    /**
     * Drops the live link if it belongs to [hostId]. Removing the keyring entry is the
     * caller's job and has to happen too — this only ends the session that entry authorised.
     */
    fun revoke(hostId: String) {
        onRevoke?.invoke(hostId)
    }

    object Source {
        const val NOTIFICATION = "notification"
        const val APP = "app"
    }
}
