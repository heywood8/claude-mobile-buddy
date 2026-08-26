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
    private val expectedRows = 9
    private val expectedColumns = 14

    @Test
    fun `every frame is the same size`() {
        for ((state, variants) in Clawd.frames) {
            for ((v, frames) in variants.withIndex()) {
                for ((f, frame) in frames.withIndex()) {
                    assertEquals("$state variant $v frame $f height", expectedRows, frame.size)
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
            assertEquals("$state variants", 3, variants!!.size)
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
