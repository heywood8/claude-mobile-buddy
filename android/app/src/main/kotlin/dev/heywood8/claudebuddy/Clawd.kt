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
        PetState.CELEBRATE -> FRAME_MILLIS
        PetState.DIZZY -> 160
    }

    /** How far the whole sprite rises, in cells. Whole numbers only — see the renderer. */
    fun bobCells(state: PetState): Float = when (state) {
        PetState.SLEEP -> 1f
        PetState.IDLE -> 1f
        PetState.BUSY -> 1f
        PetState.ATTENTION -> 2f
        PetState.CELEBRATE -> 3f
        PetState.DIZZY -> 1f
    }

    /** One half of the bob, in milliseconds. */
    fun bobMillis(state: PetState): Int = when (state) {
        PetState.SLEEP -> 2200
        PetState.IDLE -> 1600
        PetState.BUSY -> 500
        PetState.ATTENTION -> 260
        PetState.CELEBRATE -> 180
        PetState.DIZZY -> 700
    }

    fun palette(symbol: Char): Color? = when (symbol) {
        'B' -> BODY
        'L' -> LIMB
        'E' -> EYE
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

    // Four phases of a walk, and one of standing still.
    private const val STAND_A = ".L..L....L..L."
    private const val STAND_B = "L....L..L....L"
    private const val STEP_A = "..L.LL...L.L.."
    private const val STEP_B = ".L....L.L....L"
    private const val STEP_C = ".L...L..L...L."
    private const val STEP_D = "L.....LL.....L"
    private const val TUCKED = "..L........L.."
    private const val BLANK = ".............."

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
        PetState.BUSY to listOf(
            body(EYES_OPEN, EYES_OPEN, STEP_A, STEP_B),
            body(EYES_RIGHT, EYES_RIGHT, STEP_C, STEP_D),
            body(EYES_OPEN, EYES_OPEN, STEP_B, STEP_A),
            body(EYES_LEFT, EYES_LEFT, STEP_D, STEP_C),
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
        ),
        PetState.CELEBRATE to listOf(
            raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_A, STAND_B),
            raised("LL..........LL", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_A, STEP_B),
            raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_B, STAND_A),
            raised("..L........L..", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_C, STEP_D),
        ),
        PetState.DIZZY to listOf(
            body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_C, STEP_D),
            body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_A, STEP_B),
            body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_D, STEP_C),
            body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_B, STEP_A),
        ),
    )
}
