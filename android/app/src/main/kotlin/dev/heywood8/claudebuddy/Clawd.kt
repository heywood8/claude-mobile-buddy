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

    /**
     * The same row moved sideways, padding with empty cells and keeping the width.
     *
     * A whole frame shifted by a cell is a lean, and two of them either side of the original
     * are a sway — which is most of what makes a second version of an animation feel like a
     * different animation rather than the same one played again.
     */
    private fun shift(row: String, by: Int): String = when {
        by > 0 -> (".".repeat(by) + row).take(row.length)
        by < 0 -> row.drop(-by) + ".".repeat(-by)
        else -> row
    }

    private fun swayed(frame: List<String>, by: Int) = frame.map { shift(it, by) }

    /** A frame with something drawn above the head, for the states that have something to say. */
    private fun topped(frame: List<String>, top: String) = listOf(top) + frame.drop(1)

    private const val ANVIL = "..KKKKKKKKKK.."
    private const val VISOR = ".BEEEEEEEEEEB."

    /** At the anvil: five rows of crab standing behind three rows of iron. */
    private fun forge(hammer: String, anvilTop: String, face: String) = listOf(
        hammer, TOP, WIDE, face, WIDE, BOTTOM, anvilTop, "....KKKKKK....", "..KKKKKKKKKK..",
    )

    /** Behind a welding mask. The eyes are gone because that is what a visor is for. */
    private fun welding(glow: String, torch: String) = listOf(
        glow, TOP, WIDE, VISOR, WIDE, BOTTOM, torch, PLANTED, BLANK,
    )

    /** Holding something up over its head — the page, mostly. */
    private fun holding(sheet: String, face: String, faceLower: String) = listOf(
        sheet, sheet, TOP, WIDE, face, faceLower, WIDE, BOTTOM, PLANTED,
    )

    /** Sat down with its legs tucked in. */
    private fun sitting(face: String, faceLower: String) =
        listOf(BLANK, TOP, WIDE, face, faceLower, WIDE, BOTTOM, TUCKED, BLANK)

    /** The body used by every heart frame; the hearts themselves go above it. */
    private fun smitten() =
        listOf(BLANK, TOP, WIDE, EYES_HAPPY, EYES_HAPPY, WIDE, BOTTOM, PLANTED, BLANK)

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
    val frames: Map<PetState, List<List<List<String>>>> = mapOf(
        PetState.SLEEP to listOf(
            listOf(
                listOf(BLANK, TOP, WIDE, EYES_SHUT, WIDE, WIDE, BOTTOM, TUCKED, BLANK),
                listOf(BLANK, BLANK, TOP, WIDE, EYES_SHUT, WIDE, BOTTOM, TUCKED, BLANK),
                listOf(BLANK, TOP, WIDE, EYES_SHUT, WIDE, WIDE, BOTTOM, TUCKED, BLANK),
            ),
            // Dreaming out loud.
            listOf(
                topped(body(EYES_SHUT, WIDE, TUCKED, BLANK), "...........L.."),
                topped(body(EYES_SHUT, WIDE, TUCKED, BLANK), "..........L..."),
                topped(body(EYES_SHUT, WIDE, TUCKED, BLANK), ".........LL..."),
                topped(body(EYES_SHUT, WIDE, TUCKED, BLANK), BLANK),
            ),
            // Rolled over and settling.
            listOf(
                swayed(body(EYES_SHUT, WIDE, TUCKED, BLANK), -1),
                swayed(body(EYES_SHUT, WIDE, TUCKED, BLANK), -1),
                swayed(body(EYES_SHUT, WIDE, TUCKED, BLANK), 0),
                swayed(body(EYES_SHUT, WIDE, TUCKED, BLANK), 0),
            ),
        ),
        PetState.IDLE to listOf(
            listOf(
                body(EYES_OPEN, EYES_OPEN, STAND_A, STAND_B),
                body(EYES_OPEN, EYES_OPEN, STAND_B, STAND_A),
                // The blink. One frame of it, which is all a blink is.
                body(EYES_SHUT, EYES_OPEN, STAND_A, STAND_B),
                body(EYES_RIGHT, EYES_RIGHT, STAND_A, STAND_B),
            ),
            // Looking around.
            listOf(
                body(EYES_LEFT, EYES_LEFT, STAND_A, STAND_B),
                body(EYES_OPEN, EYES_OPEN, STAND_A, STAND_B),
                body(EYES_RIGHT, EYES_RIGHT, STAND_B, STAND_A),
                body(EYES_SHUT, EYES_OPEN, STAND_A, STAND_B),
            ),
            // Shifting its weight from side to side.
            listOf(
                swayed(body(EYES_OPEN, EYES_OPEN, STAND_A, STAND_B), -1),
                body(EYES_OPEN, EYES_OPEN, STAND_B, STAND_A),
                swayed(body(EYES_OPEN, EYES_OPEN, STAND_A, STAND_B), 1),
                body(EYES_SHUT, EYES_OPEN, STAND_B, STAND_A),
            ),
        ),
        // Three ways of being at work. Same meaning, different trade.
        PetState.BUSY to listOf(
            listOf(
                typing(screen = ".KSWWWSSSSSSK.", keys = "KKLKKKKKKLKKKK", face = EYES_OPEN_LOW),
                typing(screen = ".KSWWWWSSSSSK.", keys = "KKKLKKKKKKLKKK", face = EYES_OPEN_LOW),
                typing(screen = ".KSWWWWWSSSSK.", keys = "KKLKKKKKKKLKKK", face = EYES_RIGHT_LOW),
                typing(screen = ".KSWWSSSSSSSK.", keys = "KKKLKKKKKLKKKK", face = EYES_OPEN_LOW),
            ),
            // At the anvil. The hammer comes down on the third frame, which is where the
            // sparks are — a strike you can see is worth more than a hammer you can follow.
            listOf(
                forge(hammer = "....KKKK......", anvilTop = ANVIL, face = EYES_OPEN),
                forge(hammer = "...KKK........", anvilTop = ANVIL, face = EYES_OPEN),
                forge(hammer = BLANK, anvilTop = "..KKHKKKKHKK..", face = EYES_SHUT),
                forge(hammer = "..KK..........", anvilTop = "...KHKKKKKHK..", face = EYES_OPEN),
            ),
            // Welding: the arc is the animation, and the visor is why the eyes are gone.
            listOf(
                welding(glow = "....W..W......", torch = "......KKW....."),
                welding(glow = "...WWW.W.W....", torch = "......KKWW...."),
                welding(glow = BLANK, torch = "......KK......"),
                welding(glow = "....W.WW......", torch = "......KKW....."),
            ),
        ),
        PetState.ATTENTION to listOf(
            listOf(
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
                raised(
                    "LL..........LL", "LL.BBBBBBBB.LL",
                    ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STEP_A, STEP_B,
                ),
            ),
            // One claw at a time, like somebody flagging down a taxi.
            listOf(
                raised(
                    "L.............", "LL.BBBBBBBB...",
                    ".BEEEBBBBEEEB.", EYES_OPEN, STAND_A, STAND_B,
                ),
                raised(
                    ".L............", ".L.BBBBBBBB...",
                    ".BEEEBBBBEEEB.", EYES_OPEN, STAND_B, STAND_A,
                ),
                raised(
                    ".............L", "...BBBBBBBB.LL",
                    ".BEEEBBBBEEEB.", EYES_OPEN, STAND_A, STAND_B,
                ),
                raised(
                    "............L.", "...BBBBBBBB.L.",
                    ".BEEEBBBBEEEB.", EYES_OPEN, STAND_B, STAND_A,
                ),
            ),
            // Hopping on the spot with both claws up.
            listOf(
                swayed(
                    raised(
                        "L............L", "LL.BBBBBBBB.LL",
                        ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STEP_A, STEP_B,
                    ),
                    -1,
                ),
                raised(
                    "L............L", "LL.BBBBBBBB.LL",
                    ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STAND_A, STAND_B,
                ),
                swayed(
                    raised(
                        "L............L", "LL.BBBBBBBB.LL",
                        ".BEEEBBBBEEEB.", ".BEEEBBBBEEEB.", STEP_C, STEP_D,
                    ),
                    1,
                ),
                raised(
                    "L............L", "LL.BBBBBBBB.LL",
                    ".BEEEBBBBEEEB.", EYES_OPEN, STAND_B, STAND_A,
                ),
            ),
        ),
        PetState.FINISHED to listOf(
            listOf(
                holding("...WWWWWWWW...", EYES_UP_TOP, EYES_UP_LOW),
                holding("..WWWWWWWW....", EYES_UP_TOP, EYES_UP_LOW),
                holding("...WWWWWWWW...", EYES_UP_TOP, EYES_UP_LOW),
                holding("....WWWWWWWW..", EYES_UP_TOP, EYES_UP_LOW),
            ),
            // Peering over the top of it to see whether anyone is coming.
            listOf(
                holding("...WWWWWWWW...", EYES_OPEN, EYES_OPEN),
                holding("...WWWWWWWW...", EYES_UP_TOP, EYES_UP_LOW),
                holding("...WWWWWWWW...", EYES_RIGHT, EYES_RIGHT),
                holding("...WWWWWWWW...", EYES_OPEN, EYES_OPEN),
            ),
            // Offering it: held out, then up, then out again.
            listOf(
                holding("..WWWWWWWWWW..", EYES_OPEN, EYES_OPEN),
                holding("...WWWWWWWW...", EYES_UP_TOP, EYES_UP_LOW),
                holding("....WWWWWW....", EYES_UP_TOP, EYES_UP_LOW),
                holding("...WWWWWWWW...", EYES_OPEN, EYES_OPEN),
            ),
        ),
        PetState.RESTING to listOf(
            listOf(
                sitting(EYES_HALF_TOP, EYES_HALF_LOW),
                sitting(EYES_OPEN, EYES_OPEN),
                sitting(EYES_SHUT, EYES_SHUT),
                sitting(EYES_LEFT, EYES_LEFT),
            ),
            // Nodding off and catching itself.
            listOf(
                sitting(EYES_SHUT, EYES_SHUT),
                swayed(sitting(EYES_SHUT, EYES_SHUT), 1),
                sitting(EYES_OPEN, EYES_OPEN),
                sitting(EYES_HALF_TOP, EYES_HALF_LOW),
            ),
            // Rocking gently, eyes on nothing in particular.
            listOf(
                swayed(sitting(EYES_HALF_TOP, EYES_HALF_LOW), -1),
                sitting(EYES_LEFT, EYES_LEFT),
                swayed(sitting(EYES_HALF_TOP, EYES_HALF_LOW), 1),
                sitting(EYES_RIGHT, EYES_RIGHT),
            ),
        ),
        PetState.CELEBRATE to listOf(
            listOf(
                raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_A, STAND_B),
                raised("LL..........LL", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_A, STEP_B),
                raised("L............L", ".L.BBBBBBBB.L.", EYES_OPEN, EYES_HAPPY, STAND_B, STAND_A),
                raised("..L........L..", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STEP_C, STEP_D),
            ),
            // A lap of honour, side to side.
            listOf(
                swayed(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STEP_A, STEP_B),
                    -1,
                ),
                raised("LL..........LL", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STAND_A, STAND_B),
                swayed(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STEP_C, STEP_D),
                    1,
                ),
                raised("LL..........LL", "L..BBBBBBBB..L", EYES_HAPPY, EYES_HAPPY, STAND_B, STAND_A),
            ),
            // Throwing confetti, one claw then the other.
            listOf(
                topped(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STAND_A, STAND_B),
                    "H...........H.",
                ),
                topped(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STEP_A, STEP_B),
                    ".H.........H..",
                ),
                topped(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STAND_B, STAND_A),
                    "..H.......H...",
                ),
                topped(
                    raised("L............L", "LL.BBBBBBBB.LL", EYES_HAPPY, EYES_HAPPY, STEP_C, STEP_D),
                    BLANK,
                ),
            ),
        ),
        PetState.DIZZY to listOf(
            listOf(
                body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_C, STEP_D),
                body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_A, STEP_B),
                body(".BEBEBBBBEBEB.", ".BBEBBBBBBEBB.", STEP_D, STEP_C),
                body(".BBEBBBBBBEBB.", ".BEBEBBBBEBEB.", STEP_B, STEP_A),
            ),
            // Staggering.
            listOf(
                swayed(body(".BEBEBBBBEBEB.", EYES_SHUT, STEP_A, STEP_B), -1),
                body(".BBEBBBBBBEBB.", EYES_SHUT, STEP_C, STEP_D),
                swayed(body(".BEBEBBBBEBEB.", EYES_SHUT, STEP_B, STEP_A), 1),
                body(".BBEBBBBBBEBB.", EYES_SHUT, STEP_D, STEP_C),
            ),
            // Seeing stars.
            listOf(
                topped(body(EYES_SHUT, ".BEBEBBBBEBEB.", STEP_A, STEP_B), "...W....W....."),
                topped(body(EYES_SHUT, ".BBEBBBBBBEBB.", STEP_C, STEP_D), "..W......W...."),
                topped(body(EYES_SHUT, ".BEBEBBBBEBEB.", STEP_B, STEP_A), "...W....W....."),
                topped(body(EYES_SHUT, ".BBEBBBBBBEBB.", STEP_D, STEP_C), BLANK),
            ),
        ),
        PetState.HEART to listOf(
            listOf(
                topped(smitten(), "..H.H........."),
                listOf("..H.H.........", "..HHH.........") + smitten().drop(2),
                listOf("...H..........", "..H.H.........") + smitten().drop(2),
                topped(smitten(), "...H.........."),
            ),
            // Two of them, drifting apart.
            listOf(
                topped(smitten(), "..H.......H..."),
                topped(smitten(), ".H.........H.."),
                topped(smitten(), "H...........H."),
                topped(smitten(), BLANK),
            ),
            // One big one, beating.
            listOf(
                topped(smitten(), "....HH.HH....."),
                listOf("...HHHHHHH....", "....HHHHH.....") + smitten().drop(2),
                listOf("....HH.HH.....", ".....HHH......") + smitten().drop(2),
                topped(smitten(), ".....H.H......"),
            ),
        ),
    )
}
