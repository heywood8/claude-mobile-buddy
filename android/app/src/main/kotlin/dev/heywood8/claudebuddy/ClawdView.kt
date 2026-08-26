package dev.heywood8.claudebuddy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
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
fun ClawdView(state: PetState, modifier: Modifier = Modifier) {
    val frames = Clawd.frames[state] ?: return

    var index by remember(state) { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        // Frame holds are per state and per frame, because a blink and a stomp are not the
        // same thing at the same rate: eight frames a second is right for stomping and reads
        // as a nervous tic on a pair of eyes.
        index = 0
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
        ),
        label = "bob",
    )

    val frame = frames[index % frames.size]
    val rows = frame.size
    val cols = frame.maxOf { it.length }

    Canvas(modifier.fillMaxWidth().height(104.dp)) {
        val cell = floor(minOf(size.width / cols, size.height / rows))
        if (cell < 1f) return@Canvas
        val originX = ((size.width - cell * cols) / 2f).roundToInt().toFloat()
        // Whole cells, so the crab hops rather than drifts.
        val lift = (bob * Clawd.bobCells(state)).roundToInt() * cell
        val originY = ((size.height - cell * rows) / 2f).roundToInt().toFloat() - lift

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
