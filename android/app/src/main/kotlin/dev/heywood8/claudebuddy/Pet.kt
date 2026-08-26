package dev.heywood8.claudebuddy

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
     * What the pet should be doing, given what the bridge last said.
     *
     * Order matters more than the states do: a waiting decision outranks everything else on
     * the screen, and your own last tap outranks the ambient state, so that pressing a button
     * is visibly acknowledged.
     */
    fun state(
        running: Boolean,
        linked: Boolean,
        snapshot: Snapshot?,
        lastAnswer: BuddyState.Answer?,
        /** This phone's clock. Deliberately not the host's — see below. */
        phoneNow: Long,
    ): PetState {
        if (!running || !linked || snapshot == null) return PetState.SLEEP
        if (snapshot.prompt != null) return PetState.ATTENTION

        // Your tap was timed by this phone, so it is measured against this phone. Everything
        // else on this screen lives in the host's frame; mixing the two here would make a
        // reaction last a second longer or shorter than it looks, for no reason anyone could
        // find later.
        if (lastAnswer != null && phoneNow - lastAnswer.at < REACTION_SECONDS) {
            return if (lastAnswer.verdict == Verdict.ONCE) PetState.CELEBRATE else PetState.DIZZY
        }

        // Somebody answered in the terminal. Worth reacting to as much as your own tap is —
        // and measured in the host's frame, since both stamps came from there. The id check
        // stops him celebrating twice over a decision you made here.
        val resolved = snapshot.resolved
        if (resolved != null &&
            resolved.id != lastAnswer?.id &&
            snapshot.now - resolved.at in 0 until REACTION_SECONDS
        ) {
            when (resolved.how) {
                "allowed" -> return PetState.CELEBRATE
                "denied" -> return PetState.DIZZY
            }
        }

        val active = snapshot.sessions.any { snapshot.now - it.active < BUSY_SECONDS }
        return if (active) PetState.BUSY else PetState.IDLE
    }

    /**
     * The mood of one session's own crab.
     *
     * The prompt does not name a session — the wire format never needed it to, since only one
     * request is on screen at a time — so the working directory is what ties them together.
     * Two sessions in the same checkout will both look worried about the same request, which
     * is a fair description of the situation.
     */
    fun sessionState(session: SessionSummary, snapshot: Snapshot): PetState {
        val prompt = snapshot.prompt
        if (prompt != null && prompt.cwd.isNotEmpty() && prompt.cwd == session.cwd) {
            return PetState.ATTENTION
        }

        // Its own request, answered somewhere else. This is the one that carries a session id,
        // so unlike the prompt it lands on exactly the right crab.
        val resolved = snapshot.resolved
        if (resolved != null &&
            resolved.session == session.id &&
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

    /** What the pet is doing, in words, for the line under it. */
    fun caption(state: PetState): String = when (state) {
        PetState.SLEEP -> "asleep"
        PetState.IDLE -> "waiting around"
        PetState.BUSY -> "watching them work"
        PetState.FINISHED -> "has something for you"
        PetState.RESTING -> "waiting on you"
        PetState.ATTENTION -> "needs you"
        PetState.CELEBRATE -> "pleased"
        PetState.DIZZY -> "shaken"
    }
}
