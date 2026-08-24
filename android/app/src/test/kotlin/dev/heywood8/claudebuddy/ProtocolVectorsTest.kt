package dev.heywood8.claudebuddy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Runs the app against `docs/protocol/fixtures/vectors.json` — the same file the bridge's
 * Swift tests read. Neither implementation produced it; see the fixtures README.
 */
class ProtocolVectorsTest {

    // MARK: - Key derivation

    @Test
    fun `derives the key chain from the pre-shared key`() {
        val keys = vectors.keys
        val derived = SessionKeys.derive(
            psk = keys.pskHex.hexToBytes(),
            hostSalt = keys.hostSaltHex.hexToBytes(),
            phoneSalt = keys.phoneSaltHex.hexToBytes(),
        )

        assertEquals(keys.sessionHex, derived.session.toHex())
        assertEquals(keys.hostToPhoneHex, derived.hostToPhone.toHex())
        assertEquals(keys.phoneToHostHex, derived.phoneToHost.toHex())
    }

    // MARK: - Frames

    @Test
    fun `seals every frame to the expected ciphertext`() {
        for (frame in vectors.frames) {
            val sealed = Aead.seal(frame.plaintext.toByteArray(), frame.counter, keyFor(frame.direction))
            assertEquals(
                "direction ${frame.direction} counter ${frame.counter}",
                frame.ciphertextBase64,
                Base64.getEncoder().encodeToString(sealed),
            )
        }
    }

    @Test
    fun `opens every frame back to its plaintext`() {
        for (frame in vectors.frames) {
            val blob = Base64.getDecoder().decode(frame.ciphertextBase64)
            val opened = Aead.open(blob, frame.counter, keyFor(frame.direction))
            assertEquals(frame.plaintext, String(opened, Charsets.UTF_8))
        }
    }

    @Test
    fun `refuses a tampered frame`() {
        val frame = vectors.frames[0]
        val blob = Base64.getDecoder().decode(frame.ciphertextBase64)
        blob[0] = (blob[0].toInt() xor 0x01).toByte()

        assertThrowsCrypto { Aead.open(blob, frame.counter, keyFor(frame.direction)) }
    }

    @Test
    fun `refuses a frame opened under the wrong counter`() {
        val frame = vectors.frames[0]
        val blob = Base64.getDecoder().decode(frame.ciphertextBase64)

        // The counter is authenticated, so renumbering a frame in flight cannot shift the
        // stream — it simply fails to open.
        assertThrowsCrypto { Aead.open(blob, frame.counter + 1, keyFor(frame.direction)) }
    }

    // MARK: - Counter discipline

    @Test
    fun `round-trips a channel in order`() {
        val (sending, receiving) = channelPair()
        for (text in listOf("first", "second", "third")) {
            val line = sending.seal(text.toByteArray())
            val opened = receiving.open(line.stripNewline())
            assertEquals(text, String(opened, Charsets.UTF_8))
        }
    }

    @Test
    fun `refuses a gap in the counter`() {
        val (sending, receiving) = channelPair()
        sending.seal("dropped".toByteArray())
        val second = sending.seal("arrives".toByteArray())

        // A write that never made it must surface as a torn session rather than silently
        // desynchronising the stream.
        assertThrowsCrypto { receiving.open(second.stripNewline()) }
    }

    @Test
    fun `refuses a replayed frame`() {
        val (sending, receiving) = channelPair()
        val line = sending.seal("once".toByteArray())
        receiving.open(line.stripNewline())

        assertThrowsCrypto { receiving.open(line.stripNewline()) }
    }

    // MARK: - Pairing

    @Test
    fun `parses valid pairing codes`() {
        for (expected in vectors.pairing.valid) {
            val parsed = PairingCode.parse(expected.url)
            assertNotNull(expected.url, parsed)
            assertEquals(expected.hostId, parsed!!.hostId)
            assertArrayEquals(expected.keyHex.hexToBytes(), parsed.key)
            assertEquals(expected.name, parsed.name)
        }
    }

    @Test
    fun `rejects malformed pairing codes`() {
        for (url in vectors.pairing.invalid) {
            assertNull(url, PairingCode.parse(url))
        }
    }

    // MARK: - Message shapes

    @Test
    fun `decodes the snapshot carried in the vectors`() {
        val frame = vectors.frames.first { it.plaintext.contains("\"t\":\"snap\"") }
        val snapshot = Wire.decodeSnapshot(frame.plaintext.toByteArray())

        assertNotNull(snapshot)
        assertEquals(2, snapshot!!.total)
        assertEquals(1, snapshot.waiting)
        assertEquals("req_abc123", snapshot.prompt?.id)
        assertEquals("Bash", snapshot.prompt?.tool)
        assertEquals("rm -rf /tmp/foo", snapshot.prompt?.hint)
    }

    @Test
    fun `encodes a decision the way the bridge expects`() {
        val frame = vectors.frames.first { it.plaintext.contains("\"cmd\":\"permission\"") }
        val encoded = Wire.encode(Decision(id = "req_abc123", decision = Verdict.ONCE))

        // Key order is not part of the protocol, so compare the parsed object rather than
        // the bytes — that is the same reason the frame envelope is not pinned either.
        val expected = json.decodeFromString<Decision>(frame.plaintext)
        val actual = json.decodeFromString<Decision>(String(encoded, Charsets.UTF_8).trim())
        assertEquals(expected, actual)
    }

    // MARK: - Helpers

    private fun keyFor(direction: String): ByteArray =
        if (direction == "h2p") {
            vectors.keys.hostToPhoneHex.hexToBytes()
        } else {
            vectors.keys.phoneToHostHex.hexToBytes()
        }

    private fun channelPair(): Pair<SessionChannel, SessionChannel> {
        val send = vectors.keys.hostToPhoneHex.hexToBytes()
        val receive = vectors.keys.phoneToHostHex.hexToBytes()
        return SessionChannel(send, receive) to SessionChannel(receive, send)
    }

    private fun assertThrowsCrypto(block: () -> Unit) {
        try {
            block()
            fail("expected the frame to be rejected")
        } catch (expected: CryptoException) {
            // what we wanted
        }
    }

    private fun ByteArray.stripNewline(): ByteArray =
        if (isNotEmpty() && last() == '\n'.code.toByte()) copyOf(size - 1) else this

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /**
         * Walk up from the working directory rather than copying the file into the module,
         * so both test suites read the one file in the repository.
         */
        val vectors: Vectors by lazy {
            var directory: File? = File(System.getProperty("user.dir") ?: ".")
            while (directory != null) {
                val candidate = File(directory, "docs/protocol/fixtures/vectors.json")
                if (candidate.isFile) return@lazy json.decodeFromString(candidate.readText())
                directory = directory.parentFile
            }
            error("cannot find docs/protocol/fixtures/vectors.json above ${System.getProperty("user.dir")}")
        }
    }
}

// MARK: - Fixture shapes

@Serializable
data class Vectors(
    val keys: VectorKeys,
    val frames: List<VectorFrame>,
    val pairing: VectorPairing,
)

@Serializable
data class VectorKeys(
    val pskHex: String,
    val hostSaltHex: String,
    val phoneSaltHex: String,
    val sessionHex: String,
    val hostToPhoneHex: String,
    val phoneToHostHex: String,
)

@Serializable
data class VectorFrame(
    val direction: String,
    val counter: Long,
    val plaintext: String,
    val ciphertextBase64: String,
)

@Serializable
data class VectorPairing(
    val valid: List<VectorPairingValid>,
    val invalid: List<String>,
)

@Serializable
data class VectorPairingValid(
    val url: String,
    val hostId: String,
    val keyHex: String,
    val name: String,
)
