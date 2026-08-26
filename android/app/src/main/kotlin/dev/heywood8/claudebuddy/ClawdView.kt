package dev.heywood8.claudebuddy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws the pixel crab, one rectangle per cell.
 *
 * Two things keep it looking like pixel art rather than like a scaled-up picture of pixel art:
 * the cell size is a whole number of device pixels, and the bob moves by whole cells. Sub-pixel
 * motion on a sprite this size reads as the whole character wobbling out of focus.
 */
@Composable
fun ClawdView(
    state: PetState,
    modifier: Modifier = Modifier,
    /**
     * Anything stable and arbitrary — a session id will do.
     *
     * Several crabs on one screen blinking on the same beat stop reading as several animals
     * and start reading as a repeating background. Offsetting each one's frame and bob by a
     * number derived from what it represents is enough to break that, and costs nothing.
     */
    phase: Int = 0,
) {
    val frames = Clawd.frames[state] ?: return
    val offset = (phase % 100_000).let { if (it < 0) -it else it }

    var index by remember(state, phase) { mutableIntStateOf(offset % frames.size) }
    LaunchedEffect(state, phase) {
        // Frame holds are per state and per frame, because a blink and a stomp are not the
        // same thing at the same rate: eight frames a second is right for stomping and reads
        // as a nervous tic on a pair of eyes.
        index = offset % frames.size
        while (true) {
            delay(Clawd.hold(state, index))
            index++
        }
    }

    val transition = rememberInfiniteTransition(label = "clawd")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Clawd.bobMillis(state), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(offset % Clawd.bobMillis(state)),
        ),
        label = "bob",
    )

    val frame = frames[index % frames.size]
    val rows = frame.size
    val cols = frame.maxOf { it.length }

    // The caller owns the size. He is a status widget in one place and a speaker in another,
    // and the two want different room.
    Canvas(modifier) {
        // The jump needs somewhere to go. Fitting the sprite to the box exactly and then
        // lifting it sent the head out through the top of the box — and clipped, since a
        // Canvas draws inside its own bounds and nowhere else.
        val tall = rows + Clawd.MAX_BOB_CELLS
        val cell = floor(minOf(size.width / cols, size.height / tall))
        if (cell < 1f) return@Canvas

        val originX = ((size.width - cell * cols) / 2f).roundToInt().toFloat()
        // Whole cells, so the crab hops rather than drifts.
        val lift = (bob * Clawd.bobCells(state)).roundToInt() * cell
        // Resting at the bottom of the reserved space, jumping up into it.
        val rest = ((size.height - cell * tall) / 2f).roundToInt().toFloat() +
            cell * Clawd.MAX_BOB_CELLS
        val originY = rest - lift

        for (row in frame.indices) {
            val line = frame[row]
            for (column in line.indices) {
                val color = Clawd.palette(line[column]) ?: continue
                drawRect(
                    color = color,
                    topLeft = Offset(originX + column * cell, originY + row * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}
