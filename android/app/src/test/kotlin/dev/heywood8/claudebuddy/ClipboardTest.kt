package dev.heywood8.claudebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape pinned here is pinned identically in the bridge's suite
 * (`ClipboardTests.swift`). It is not in `vectors.json`: that file pins ciphertexts for fixed
 * plaintexts, and a new message type does not change any sealed bytes.
 *
 * [Clipboard] itself is not exercised here — it reads and writes the system clipboard, which
 * needs a device. What is testable without one is the codec and the size cap, and those are the
 * parts that have to agree byte for byte with the other implementation.
 */
class ClipboardTest {
    private val sample = "hello from the Mac"
    private val sampleLine = """{"at":1775731300,"b":"aGVsbG8gZnJvbSB0aGUgTWFj","t":"clip"}"""

    @Test
    fun `encodes to the shape both implementations agree on`() {
        val encoded = String(Wire.encodePayload(Clip.of(sample, 1775731300)), Charsets.UTF_8)
        // Compared as parsed objects rather than as text: key order is not part of the
        // protocol, and the two serialisers do not agree on it.
        assertEquals(
            Wire.json.parseToJsonElement(sampleLine),
            Wire.json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun `round-trips text through base64`() {
        assertEquals(sample, Clip.of(sample, 1).text)
        assertEquals("", Clip.of("", 1).text)
        assertEquals("ключ · 鍵 · 🦀", Clip.of("ключ · 鍵 · 🦀", 1).text)
    }

    @Test
    fun `reads as a clip off the wire, and a snapshot still reads as a snapshot`() {
        val inbound = Wire.decodeInbound(sampleLine.toByteArray(Charsets.UTF_8))
        assertTrue("not decoded as a clip", inbound is Inbound.Clipboard)
        assertEquals(sample, (inbound as Inbound.Clipboard).clip.text)
        assertEquals(1775731300L, inbound.clip.at)

        val snap = Wire.decodeInbound("""{"t":"snap","waiting":2}""".toByteArray())
        assertTrue(snap is Inbound.Snap)
        assertEquals(2, (snap as Inbound.Snap).snapshot.waiting)
    }

    /**
     * Snapshot defaults every field, so it decodes happily from any object at all. Dispatching
     * on `t` is what stops it swallowing every message added after it — including this one.
     */
    @Test
    fun `does not decode a clip as an empty snapshot`() {
        assertNull(Wire.decodeSnapshot(sampleLine.toByteArray(Charsets.UTF_8)))
        assertNull(Wire.decodeInbound("""{"t":"bye","reason":"shutdown"}""".toByteArray()))
    }

    @Test
    fun `a clip whose payload is not base64, or not UTF-8 inside it, decodes to nothing`() {
        assertNull(Clip(b = "not base64!!").text)
        assertNull(Clip(b = "//4=").text) // 0xFF 0xFE, not legal UTF-8 anywhere
    }

    @Test
    fun `clamps to the byte limit`() {
        val clipped = Clip.of("a".repeat(Clip.TEXT_LIMIT + 500), 0).text
        assertNotNull(clipped)
        assertEquals(Clip.TEXT_LIMIT, clipped!!.toByteArray(Charsets.UTF_8).size)
        // No ellipsis: a clipboard is pasted, and a marker on the end would be pasted too.
        assertTrue(!clipped.endsWith("…"))
    }

    @Test
    fun `clamps on a character boundary rather than through one`() {
        // Four bytes each, so the limit falls inside a character rather than between two.
        val clipped = Clip.of("🦀".repeat(Clip.TEXT_LIMIT), 0).text
        assertNotNull("cut mid-character, so it did not decode", clipped)
        val bytes = clipped!!.toByteArray(Charsets.UTF_8).size
        assertTrue("clamped to $bytes bytes", bytes <= Clip.TEXT_LIMIT)
        assertTrue("threw away a whole character to get there", bytes > Clip.TEXT_LIMIT - 4)
        assertTrue(clipped.all { it.isSurrogate() })
    }

    /**
     * Every outcome reaches a person as a toast, from a button they tapped once, so each one
     * has to say a different and non-empty thing. The compiler makes the `when` exhaustive; it
     * has nothing to say about two branches quietly sharing a sentence.
     */
    @Test
    fun `every outcome says something, and says something different`() {
        val messages = Clipboard.Outcome.entries.map { it.message }
        assertTrue("an outcome has no message", messages.all { it.isNotBlank() })
        assertEquals(
            "two outcomes share a message",
            Clipboard.Outcome.entries.size,
            messages.toSet().size,
        )
    }

    /**
     * The reason the text travels base64 at all.
     *
     * As a JSON string, a control character costs six bytes rather than one, so a clip of the
     * wrong shape would seal to a line over [Wire.MAX_LINE] — and an oversized line is not
     * dropped politely. [LineAssembler] throws it away, whatever follows the newline decrypts
     * as garbage, and the session ends. This is the test that says it cannot happen.
     */
    @Test
    fun `a worst-case clip still seals under the line cap`() {
        val key = ByteArray(32)
        val overlong = listOf(
            // The six-bytes-per-character case, written as a code point so that this file
            // stays free of literal control characters.
            Char(1).toString().repeat(Clip.TEXT_LIMIT * 2),
            "\"".repeat(Clip.TEXT_LIMIT * 2),
            "🦀".repeat(Clip.TEXT_LIMIT),
            "a".repeat(Clip.TEXT_LIMIT * 2),
        )
        for (text in overlong) {
            val payload = Wire.encodePayload(Clip.of(text, 9999999999))
            val line = SessionChannel(sendKey = key, receiveKey = key).seal(payload)
            assertTrue("sealed to ${line.size} bytes", line.size <= Wire.MAX_LINE)
        }
    }
}
