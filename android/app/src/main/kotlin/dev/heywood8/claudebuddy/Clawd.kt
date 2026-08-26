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
 * Frames are square-ish grids of equal-length rows. The renderer scales them by whole numbers
 * only, so the pixels stay pixels instead of turning into a smudge.
 */
object Clawd {
    /** `#DA7758`, the body colour the mascot is known by. */
    val BODY = Color(0xFFDA7758)

    /** Slightly lighter, as in the original, so limbs read against the shell. */
    val LIMB = Color(0xFFDD775B)

    val EYE = Color(0xFF241A15)

    /** How long a frame is held. The original stomps at eight frames a second. */
    const val FRAME_MILLIS = 125L

    /**
     * How long each frame is held.
     *
     * Per state and per frame, because a blink and a stomp are not the same event played at
     * different speeds. Idle holds its open-eyed frame for two and a half seconds and the
     * closed one for a seventh of a second; the same pair at an even rate is a twitch.
     */
    fun hold(state: PetState, frame: Int): Long = when (state) {
        PetState.IDLE -> if (frame % 2 == 0) 2600 else 140
        PetState.SLEEP -> 1400
        PetState.BUSY -> 220
        PetState.ATTENTION -> 320
        PetState.CELEBRATE -> FRAME_MILLIS
        PetState.DIZZY -> 180
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

    /**
     * Frames per state, in the order they play.
     *
     * Where a state needs only motion rather than new art — a bob, a lean — it gets one frame
     * and the motion happens in the renderer. Adding frames is a text edit; adding a state is
     * a line here and a line in [PetState].
     */
    val frames: Map<PetState, List<List<String>>> = mapOf(
        PetState.SLEEP to listOf(
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                "..L........L..",
                "..............",
            ),
            listOf(
                "..............",
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                "..L........L..",
                "..............",
            ),
        ),
        PetState.IDLE to listOf(
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                ".L..L....L..L.",
                "L....L..L....L",
            ),
            // The blink. One frame of it, which is all a blink is.
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                ".L..L....L..L.",
                "L....L..L....L",
            ),
        ),
        PetState.BUSY to listOf(
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                ".L..L....L..L.",
                "L....L..LL...L",
            ),
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                "..L.LL...L.L..",
                ".L....L.L....L",
            ),
        ),
        PetState.ATTENTION to listOf(
            listOf(
                "L............L",
                "LL.BBBBBBBB.LL",
                ".BBBBBBBBBBBB.",
                ".BEEEBBBBEEEB.",
                ".BEEEBBBBEEEB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                ".L..L....L..L.",
                "L....L..L....L",
            ),
            listOf(
                "LL..........LL",
                "L..BBBBBBBB..L",
                ".BBBBBBBBBBBB.",
                ".BEEEBBBBEEEB.",
                ".BEEEBBBBEEEB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                "L...L....L...L",
                ".L...L..L...L.",
            ),
        ),
        PetState.CELEBRATE to listOf(
            listOf(
                "L............L",
                ".L.BBBBBBBB.L.",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBEEBBBBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                ".L..L....L..L.",
                "L....L..L....L",
            ),
            listOf(
                "..............",
                "L..BBBBBBBB..L",
                ".BBBBBBBBBBBB.",
                ".BBEEBBBBEEBB.",
                ".BBBBBEEBBBBB.",
                ".BBBBBBBBBBBB.",
                "..BBBBBBBBBB..",
                "..L.L....L.L..",
                ".L...L..L...L.",
            ),
        ),
        PetState.DIZZY to listOf(
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BEBEBBBBEBEB.",
                ".BBEBBBBBBEBB.",
                ".BEBEBBBBEBEB.",
                "..BBBBBBBBBB..",
                ".L...L..L...L.",
                "L.....LL.....L",
            ),
            listOf(
                "..............",
                "..BBBBBBBBBB..",
                ".BBBBBBBBBBBB.",
                ".BBEBBBBBBEBB.",
                ".BEBEBBBBEBEB.",
                ".BBEBBBBBBEBB.",
                "..BBBBBBBBBB..",
                "L...L....L...L",
                ".L....LL....L.",
            ),
        ),
    )
}
