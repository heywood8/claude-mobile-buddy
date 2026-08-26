package dev.heywood8.claudebuddy

import androidx.compose.runtime.mutableStateOf

/**
 * The part of the app that has no job.
 *
 * Everything else here answers a question — what is waiting, which session, how long. The pet
 * answers none, and that is the point: a glance should say how things are going before you
 * have read a word. The states are ones the bridge already reports, so this is a rendering of
 * existing state rather than a second source of truth. [Clawd] holds the pixels.
 */
enum class PetState {
    /** Nothing is linked. Nobody is going to ask you anything. */
    SLEEP,

    /** Linked, and nothing has happened for a while. */
    IDLE,

    /** A session did something recently. */
    BUSY,

    /** It has answered and you have not come back to it yet. */
    FINISHED,

    /** The answer has been sitting there a while, and you have not replied. */
    RESTING,

    /** Something is waiting for you right now. */
    ATTENTION,

    /** You just allowed something. */
    CELEBRATE,

    /** You just denied something. */
    DIZZY,

    /** You touched it. It has no other purpose and does not need one. */
    HEART,

    /**
     * Asking to run a shell command.
     *
     * The one state that is a joke rather than a signal: bash, breaker, horns. It carries the
     * same meaning as [ATTENTION] and appears only when the tool is `Bash`, which happens to
     * be the tool worth looking twice at anyway.
     */
    BREAKER,
}

/**
 * A mood that overrides what the bridge says, briefly.
 *
 * Everything else on this screen is derived from state the host reports. These three are not:
 * a tap, a shake and the phone being turned face down happen here and are gone in seconds.
 * Kept apart from [Pet] for exactly that reason — one is a rendering of somebody else's truth,
 * the other is this device reacting to being handled.
 */
object PetMood {
    /** The mood, who it is for — empty means everyone — and when it lapses. */
    private data class Mood(val state: PetState, val session: String, val until: Long)

    private val current = mutableStateOf<Mood?>(null)

    fun show(state: PetState, seconds: Long, session: String = "") {
        current.value = Mood(state, session, System.currentTimeMillis() / 1000 + seconds)
    }

    fun forSession(id: String, now: Long): PetState? = current.value
        ?.takeIf { now < it.until && (it.session.isEmpty() || it.session == id) }
        ?.state
}

object Pet {
    /** How long a reaction to your own tap lasts before the pet settles back down. */
    const val REACTION_SECONDS = 6L

    /** How recently a session must have done something to count as busy. */
    private const val BUSY_SECONDS = 45L

    /** How long an answer counts as unread before the crab gives up waiting for you. */
    private const val UNREAD_SECONDS = 180L

    /**
     * Tokens per level.
     *
     * Upstream celebrates every 50K, counting its own way. This counts new tokens across every
     * session on the host, which is a much larger number: at 50K a level, a morning's work put
     * the crab at level eight hundred, and a level nobody can feel is decoration on decoration.
     */
    const val TOKENS_PER_LEVEL = 1_000_000L

    fun level(tokens: Long): Int = (tokens / TOKENS_PER_LEVEL).toInt()

    /**
     * The mood of one session's own crab.
     *
     * Order matters more than the states do. A request outranks everything else on the screen;
     * a decision just taken outranks the ambient state, so that answering is acknowledged; and
     * working outranks having finished, since a session that started again is not waiting.
     */
    fun sessionState(
        session: SessionSummary,
        snapshot: Snapshot,
        lastAnswer: BuddyState.Answer?,
        /** This phone's clock, for the one stamp that was made here. */
        phoneNow: Long,
    ): PetState {
        val asking = snapshot.pending.firstOrNull { it.session == session.id }
        if (asking != null) {
            return if (asking.tool == "Bash") PetState.BREAKER else PetState.ATTENTION
        }

        // Your own tap. The request it answered is gone from this snapshot — answering it is
        // what removed it — so the session travels with the answer rather than being looked up.
        // Measured against this phone because this phone is what timed it; everything else
        // here lives in the host's frame, and mixing the two would make the reaction a second
        // longer or shorter than it looks for no reason anyone could find later.
        if (lastAnswer != null &&
            lastAnswer.session == session.id &&
            phoneNow - lastAnswer.at < REACTION_SECONDS
        ) {
            return if (lastAnswer.verdict == Verdict.ONCE) PetState.CELEBRATE else PetState.DIZZY
        }

        // Its own request, answered somewhere else — in the terminal, or by auto mode.
        val resolved = snapshot.resolved
        if (resolved != null &&
            resolved.session == session.id &&
            resolved.id != lastAnswer?.id &&
            snapshot.now - resolved.at in 0 until REACTION_SECONDS
        ) {
            when (resolved.how) {
                "allowed" -> return PetState.CELEBRATE
                "denied" -> return PetState.DIZZY
            }
        }
        // Working beats having finished: the stamp only clears on the next tool call, and a
        // session that has started again is not waiting for you.
        val quiet = snapshot.now - session.active
        if (session.active > 0 && quiet < BUSY_SECONDS && session.active >= session.finished) {
            return PetState.BUSY
        }

        // Nothing reports that you have read anything, so "unread" is a guess made out loud:
        // for the first few minutes after it stops, it is holding the answer up. After that it
        // assumes you saw it and went to do something else, and sits down. Wrong sometimes,
        // and this is a crab.
        if (session.finished > 0) {
            val since = snapshot.now - session.finished
            return if (since < UNREAD_SECONDS) PetState.FINISHED else PetState.RESTING
        }

        return PetState.IDLE
    }
}
