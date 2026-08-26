package dev.heywood8.claudebuddy

import androidx.compose.ui.graphics.Color

/**
 * A pixel crab, drawn the way the original is drawn: out of rectangles and nothing else.
 *
 * Anthropic's mascot is built entirely from `<rect>` elements — no paths, no curves — and its
 * animations are frame sprites with a tween or two laid over the top. That is worth copying as
 * a technique, because it makes the art code: a frame is a grid of characters you can read and
 * edit in a diff, rather than a binary nobody will ever open again. This is our own crab, not
 * theirs; the shape is a homage and the pixels are ours.
 *
 * Frames are grids of equal-length rows. The renderer scales them by whole numbers only, so
 * the pixels stay pixels instead of turning into a smudge.
 */
object Clawd {
    /** `#DA7758`, the body colour the mascot is known by. */
    val BODY = Color(0xFFDA7758)

    /** Slightly lighter, as in the original, so limbs read against the shell. */
    val LIMB = Color(0xFFDD775B)

    val EYE = Color(0xFF241A15)

    /** The answer it holds up. Warm rather than pure white, which glares next to the shell. */
    val PAPER = Color(0xFFF5F0E8)

    /** The laptop it works at: a dark screen and a pale body. */
    val SCREEN = Color(0xFF2B3440)
    val CHASSIS = Color(0xFF9AA1A8)

    /** Warmer and redder than the shell, so it is a heart and not a lump of crab. */
    val HEART = Color(0xFFE0574A)

    /** How long a frame is held by default. The original stomps at eight frames a second. */
    const val FRAME_MILLIS = 125L

    /**
     * Headroom reserved above the sprite, in cells: the tallest bob any state asks for.
     *
     * Reserved for every state rather than for the state doing the jumping, so the crab is the
     * same size whatever mood he is in. Sized to the busiest state instead would shrink him
     * the moment he got excited, which is a strange thing for a screen to do.
     */
    const val MAX_BOB_CELLS = 3

    /**
     * How long each frame is held.
     *
     * Per state and per frame, because a blink and a stomp are not the same event at different
     * speeds. Idle sits with its eyes open for a couple of seconds, shuts them for an eighth
     * of one, and looks away now and then — an even rate turns all three into a twitch.
     */
    fun hold(state: PetState, frame: Int): Long = when (state) {
        PetState.IDLE -> when (frame % 4) {
            0 -> 2400
            1 -> 1700
            2 -> 130
            else -> 900
        }
        PetState.SLEEP -> 1500
        PetState.BUSY -> 200
        PetState.ATTENTION -> 280
        PetState.FINISHED -> 260
        // Slow on purpose. Everything else on this screen moves because something is
        // happening; this one moves because nothing is.
        PetState.RESTING -> when (frame % 4) {
            2 -> 160
            else -> 1300
        }
        PetState.CELEBRATE -> FRAME_MILLIS
        PetState.DIZZY -> 160
        PetState.HEART -> 260
    }

    /** How far the whole sprite rises, in cells. Whole numbers only — see the renderer. */
    fun bobCells(state: PetState): Float = when (state) {
        PetState.SLEEP -> 1f
        PetState.IDLE -> 1f
        // Sitting at a desk. The claws on the keys are the movement; a bobbing laptop is not.
        PetState.BUSY -> 0f
        PetState.ATTENTION -> 2f
        PetState.CELEBRATE -> 3f
        PetState.DIZZY -> 1f
        PetState.HEART -> 1f
        // Nothing. He is standing still and waving the page; a hop underneath it would put
        // the whole animal back in motion, which is the thing that read as walking.
        PetState.FINISHED -> 0f
        PetState.RESTING -> 1f
    }

    /** One half of the bob, in milliseconds. */
    fun bobMillis(state: PetState): Int = when (state) {
        PetState.SLEEP -> 2200
        PetState.IDLE -> 1600
        PetState.BUSY -> 500
        PetState.ATTENTION -> 260
        PetState.CELEBRATE -> 180
        PetState.DIZZY -> 700
        PetState.HEART -> 900
        PetState.FINISHED -> 420
        PetState.RESTING -> 3000
    }

    fun palette(symbol: Char): Color? = when (symbol) {
        'B' -> BODY
        'L' -> LIMB
        'E' -> EYE
        'W' -> PAPER
        'S' -> SCREEN
        'K' -> CHASSIS
        'H' -> HEART
        else -> null
    }

    // The shell, which every frame shares. Faces and legs are what change.
    private const val TOP = "..BBBBBBBBBB.."
    private const val WIDE = ".BBBBBBBBBBBB."
    private const val BOTTOM = "..BBBBBBBBBB.."

    private const val EYES_OPEN = ".BBEEBBBBEEBB."
    private const val EYES_RIGHT = ".BBBEEBBEEBBB."
    private const val EYES_LEFT = ".BEEBBBBBBEEB."
    private const val EYES_SHUT = ".BBBBBBBBBBBB."
    private const val EYES_HAPPY = ".BBBBBEEBBBBB."

    /** Looking up at whatever it is holding over its head. */
    private const val EYES_UP_TOP = ".BBEEBBBBEEBB."
    private const val EYES_UP_LOW = ".BBBBBBBBBBBB."

    /** Half shut, the way a thing that has been waiting a while looks. */
    private const val EYES_HALF_TOP = ".BBBBBBBBBBBB."
    private const val EYES_HALF_LOW = ".BBEEBBBBEEBB."

    // Four phases of a walk, and one of standing still.
    private const val STAND_A = ".L..L....L..L."
    private const val STAND_B = "L....L..L....L"
    private const val STEP_A = "..L.LL...L.L.."
    private const val STEP_B = ".L....L.L....L"
    private const val STEP_C = ".L...L..L...L."
    private const val STEP_D = "L.....LL.....L"
    private const val TUCKED = "..L........L.."

    /** Both feet down, one row, no cycle: a crab that is not going anywhere. */
    private const val PLANTED = ".L..L....L..L."
    private const val BLANK = ".............."

    /** The narrower head that fits behind a laptop, and its two eye rows. */
    private const val DESK_TOP = "...BBBBBBBB..."
    private const val DESK_WIDE = "..BBBBBBBBBB.."
    private const val EYES_OPEN_LOW = "..BBEEBBEEBB.."
    private const val EYES_RIGHT_LOW = "..BBBEEBBEEB.."

    /**
     * A frame of the crab at its laptop: head above the lid, screen, keyboard.
     *
     * The lid covers everything below the eyes, which is what makes it read as sitting at a
     * desk rather than wearing a monitor.
     */
    private fun typing(screen: String, keys: String, face: String) = listOf(
        BLANK,
        DESK_TOP,
        DESK_WIDE,
        face,
        DESK_WIDE,
        screen,
        ".KSSSSSSSSSSK.",
        keys,
        BLANK,
    )

    private fun body(face: String, faceLower: String, legsUpper: String, legsLower: String) =
        listOf(BLANK, TOP, WIDE, face, faceLower, WIDE, BOTTOM, legsUpper, legsLower)

    /** Claws raised above the shell, for the states that are asking for something. */
    private fun raised(
        claws: String,
        clawsLower: String,
        face: String,
        faceLower: String,
        legsUpper: String,
        legsLower: String,
    ) = listOf(claws, clawsLower, WIDE, face, faceLower, WIDE, BOTTOM, legsUpper, legsLower)

    /**
     * Frames per state, in the order they play.
     *
     * Adding a frame is a text edit; adding a state is a line here and a line in [PetState].
     */
    val frames: Map<PetState, List<List<String>>> = mapOf(
        PetState.SLEEP to listOf(
            listOf(BLANK, TOP, WIDE, EYES_SHUT, WIDE, WIDE, BOTTOM, TUCKED, BLANK),
            listOf(BLANK, BLANK, TOP, WIDE, EYES_SHUT, WIDE, BOTTOM, TUCKED, BLANK),
            listOf(BLANK, TOP, WIDE, EYES_SHUT, WIDE, WIDE, BOTTOM, TUCKED, BLANK),
        ),
        PetState.IDLE to listOf(
            body(EYES_OPEN, EYES_OPEN, STAND_A, STAND_B),
            body(EYES_OPEN, EYES_OPEN, STAND_B, STAND_A),
            // The blink. One frame of it, which is all a blink is.
            body(EYES_SHUT, EYES_OPEN, STAND_A, STAND_B),
            // And a glance at whatever moved.
            body(EYES_RIGHT, EYES_RIGHT, STAND_A, STAND_B),
        ),
        // Sat at a laptop, typing.
        //
        // Only the claws on the keyboard and the text on the screen move. Walking legs under a
        // working animal read as an animal on its way somewhere, which is the opposite of what
        // this state means.
        PetState.BUSY to listOf(
            typing(screen = ".KSWWWSSSSSSK.", keys = "KKLKKKKKKLKKKK", face = EYES_OPEN_LOW),
            typing(screen = ".KSWWWWSSSSSK.", keys = "KKKLKKKKKKLKKK", face = EYES_OPEN_LOW),
            typing(screen = ".KSWWWWWSSSSK.", keys = "KKLKKKKKKKLKKK", face = EYES_RIGHT_LOW),
            typing(screen = ".KSWWSSSSSSSK.", keys = "KKKLKKKKKLKKKK", face = EYES_OPEN_LOW),
        ),
        PetState.ATTENTION to listOf(
            raised(
                "L............L", "LL.BBBBBBBB.LL",
                ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STAND_A, STAND_B,
            ),
            raised(
                "LL..........LL", "L..BBBBBBBB..L",
                ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STEP_C, STEP_D,
            ),
            raised(
                "L............L", "LL.BBBBBBBB.LL",
                ".BEEEBBBBEEEB.", EYES_OPEN, STAND_B, STAND_A,
            ),
            // One insistent shake of both claws at once.
            raised(
                "LL..........LL", "LL.BBBBBBBB.LL",
                ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STEP_A, STEP_B,
            ),
        ),
        // Standing still, holding the answer up and waving it about.
        //
        // The legs are one row and identical in every frame on purpose: with the walking pair
        // underneath, a crab waving a page read as a crab going somewhere. Only the page moves.
        PetState.FINISHED to listOf(
            listOf(
                "...WWWWWWWW...", "...WWWWWWWW...", TOP,
                WIDE, EYES_UP_TOP, EYES_UP_LOW, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                "..WWWWWWWW....", "..WWWWWWWW....", TOP,
                WIDE, EYES_UP_TOP, EYES_UP_LOW, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                "...WWWWWWWW...", "...WWWWWWWW...", TOP,
                WIDE, EYES_UP_TOP, EYES_UP_LOW, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                "....WWWWWWWW..", "....WWWWWWWW..", TOP,
                WIDE, EYES_UP_TOP, EYES_UP_LOW, WIDE, BOTTOM, PLANTED,
            ),
        ),
        // Sat down with it, legs tucked in.
        PetState.RESTING to listOf(
            listOf(BLANK, TOP, WIDE, EYES_HALF_TOP, EYES_HALF_LOW, WIDE, BOTTOM, TUCKED, BLANK),
            listOf(BLANK, TOP, WIDE, EYES_OPEN, EYES_OPEN, WIDE, BOTTOM, TUCKED, BLANK),
            listOf(BLANK, TOP, WIDE, EYES_SHUT, EYES_SHUT, WIDE, BOTTOM, TUCKED, BLANK),
            listOf(BLANK, TOP, WIDE, EYES_LEFT, EYES_LEFT, WIDE, BOTTOM, TUCKED, BLANK),
        ),
        PetState.CELEBRATE to listOf(
            raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_A, STAND_B),
            raised("LL..........LL", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_A, STEP_B),
            raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_B, STAND_A),
            raised("..L........L..", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_C, STEP_D),
        ),
        // A heart, rising. Four frames of it and then whatever it was doing before.
        PetState.HEART to listOf(
            listOf(
                BLANK, "..H.H.........", TOP,
                WIDE, EYES_HAPPY, EYES_HAPPY, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                "..H.H.........", "..HHH.........", TOP,
                WIDE, EYES_HAPPY, EYES_HAPPY, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                "...H..........", "..H.H.........", TOP,
                WIDE, EYES_HAPPY, EYES_HAPPY, WIDE, BOTTOM, PLANTED,
            ),
            listOf(
                BLANK, "...H..........", TOP,
                WIDE, EYES_HAPPY, EYES_HAPPY, WIDE, BOTTOM, PLANTED,
            ),
        ),
        PetState.DIZZY to listOf(
            body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_C, STEP_D),
            body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_A, STEP_B),
            body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_D, STEP_C),
            body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_B, STEP_A),
        ),
    )
}
