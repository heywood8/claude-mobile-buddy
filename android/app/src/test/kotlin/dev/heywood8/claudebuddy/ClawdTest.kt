package dev.heywood8.claudebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The art is hand-typed grids of characters, which is what makes it readable in a diff and
 * also what makes it easy to get wrong. A row one cell short shifts everything after it and
 * reads on screen as a glitch rather than as a typo.
 */
class ClawdTest {
    private val expectedColumns = 14

    /**
     * Height is per animation rather than fixed: the breaker needs more rows than the crab to
     * fit a face in. Within one animation it must not move, or the sprite changes size between
     * frames — which reads as the whole thing lurching.
     */
    @Test
    fun `frames keep their shape within an animation`() {
        for ((state, variants) in Clawd.frames) {
            for ((v, frames) in variants.withIndex()) {
                val height = frames.first().size
                for ((f, frame) in frames.withIndex()) {
                    assertEquals("$state variant $v frame $f height", height, frame.size)
                    for ((r, row) in frame.withIndex()) {
                        assertEquals(
                            "$state variant $v frame $f row $r width",
                            expectedColumns,
                            row.length,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every state has three animations to choose from`() {
        for (state in PetState.entries) {
            val variants = Clawd.frames[state]
            assertTrue("$state has no frames", variants != null)
            assertTrue("$state has fewer than three animations", variants!!.size >= 3)
            for ((v, frames) in variants.withIndex()) {
                assertTrue("$state variant $v is empty", frames.isNotEmpty())
            }
        }
    }

    @Test
    fun `every cell is a colour or a gap`() {
        for ((state, variants) in Clawd.frames) {
            for (frame in variants.flatten()) {
                for (row in frame) {
                    for (cell in row) {
                        assertTrue(
                            "$state uses '$cell', which the palette does not know",
                            cell == '.' || Clawd.palette(cell) != null,
                        )
                    }
                }
            }
        }
    }
}
