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

        val active = snapshot.sessions.any { snapshot.now - it.active < BUSY_SECONDS }
        return if (active) PetState.BUSY else PetState.IDLE
    }

    /** What the pet is doing, in words, for the line under it. */
    fun caption(state: PetState): String = when (state) {
        PetState.SLEEP -> "asleep"
        PetState.IDLE -> "waiting around"
        PetState.BUSY -> "watching them work"
        PetState.ATTENTION -> "needs you"
        PetState.CELEBRATE -> "pleased"
        PetState.DIZZY -> "shaken"
    }
}
