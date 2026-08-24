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

    var running by mutableStateOf(false)
        private set

    /** Set by the service while it holds the link. */
    @Volatile
    var sink: ((Decision) -> Unit)? = null

    fun update(value: Snapshot) = main.post { snapshot = value }

    fun setLinked(value: Boolean) = main.post {
        linked = value
        if (!value) snapshot = null
    }

    fun setRunning(value: Boolean) = main.post { running = value }

    fun answer(id: String, verdict: Verdict) {
        sink?.invoke(Decision(id = id, decision = verdict))
    }
}
