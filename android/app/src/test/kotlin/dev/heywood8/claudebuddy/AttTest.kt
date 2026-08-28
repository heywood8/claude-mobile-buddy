package dev.heywood8.claudebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides how much goes in one GATT notification.
 *
 * This has a test because getting it wrong is not a dropped frame. `notifyCharacteristicChanged`
 * throws `IllegalArgumentException` on a value over 512 bytes, on the GATT thread, where an
 * escaping exception ends the process — taking the foreground service, the link and every queued
 * approval with it. Measured on a Pixel 7 Pro at MTU 517, which yields exactly 514 and crashed
 * on the first clipboard line long enough to need two chunks.
 */
class AttTest {
    @Test
    fun `never exceeds the attribute ceiling, whatever the MTU`() {
        for (mtu in intArrayOf(23, 100, 185, 512, 515, 517, 600, 1024, Int.MAX_VALUE)) {
            val chunk = Att.chunk(mtu)
            assertTrue("mtu $mtu gave $chunk", chunk <= Att.MAX_ATTRIBUTE)
            assertTrue("mtu $mtu gave $chunk", chunk >= Att.MIN_CHUNK)
        }
    }

    /** The exact value the Pixel negotiates, and the one that used to be 514. */
    @Test
    fun `clamps the negotiated 517 to the attribute ceiling`() {
        assertEquals(512, Att.chunk(517))
    }

    /** Below the ceiling the MTU still governs: the payload is the MTU less the ATT header. */
    @Test
    fun `follows the MTU while it is the smaller ceiling`() {
        assertEquals(180, Att.chunk(183))
        assertEquals(509, Att.chunk(512))
    }

    /**
     * A peer that never negotiates leaves the 23-byte default, which is 20 usable. The floor
     * exists so a nonsensical MTU cannot produce a zero or negative chunk and spin the pump.
     */
    @Test
    fun `never returns a chunk too small to make progress`() {
        assertEquals(20, Att.chunk(23))
        assertEquals(20, Att.chunk(0))
        assertEquals(20, Att.chunk(-5))
    }
}
