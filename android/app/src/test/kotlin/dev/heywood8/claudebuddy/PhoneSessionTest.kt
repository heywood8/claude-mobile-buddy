package dev.heywood8.claudebuddy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Drives [PhoneSession] against the recorded handshake in the shared vectors, with the salt
 * pinned so the frames it opens are the ones the bridge's Swift tests produce.
 */
class PhoneSessionTest {

    @Test
    fun `answers a known host with the recorded challenge`() {
        val session = newSession()
        val outputs = session.receive(handshake.hello.toByteArray())

        val sent = outputs.filterIsInstance<SessionOutput.Send>().single()
        val challenge = json.decodeFromString<Challenge>(String(sent.line).trim())
        val expected = json.decodeFromString<Challenge>(handshake.challenge)

        // Compared as parsed objects: key order is not part of the protocol.
        assertEquals(expected, challenge)
        assertEquals(PhoneSession.State.AWAITING_AUTH, session.state)
        assertEquals(handshake.hostName, session.host?.name)
    }

    @Test
    fun `becomes ready on the recorded auth frame`() {
        val session = newSession()
        session.receive(handshake.hello.toByteArray())

        val outputs = session.receive(frameLine(0, handshake.authCiphertextBase64))

        assertTrue(outputs.any { it is SessionOutput.Ready })
        assertEquals(PhoneSession.State.READY, session.state)
        // The host's UTC offset travels in auth so its timestamps render in its own clock.
        assertEquals(handshake.authTime[1], session.hostUtcOffsetSeconds)
    }

    @Test
    fun `replies with a ready frame the bridge can open`() {
        val session = newSession()
        session.receive(handshake.hello.toByteArray())
        val outputs = session.receive(frameLine(0, handshake.authCiphertextBase64))

        val sent = outputs.filterIsInstance<SessionOutput.Send>().single()
        val frame = json.decodeFromString<Frame>(String(sent.line).trim())
        assertEquals(0L, frame.n)

        // Opened with the derived key rather than compared byte for byte, for the same reason
        // as above.
        val plaintext = Aead.open(
            Base64.getDecoder().decode(frame.c),
            0,
            sessionKeys().phoneToHost,
        )
        val ready = json.decodeFromString<Ready>(String(plaintext, Charsets.UTF_8))
        assertEquals("ready", ready.t)
        assertEquals(DEVICE, ready.device)
        assertEquals(1, ready.proto)
    }

    @Test
    fun `carries application messages once ready`() {
        val session = readySession()

        // Counter 1 host-to-phone is the snapshot in the vectors.
        val snapshotFrame = vectors.frames.single { it.direction == "h2p" && it.counter == 1L }
        val outputs = session.receive(frameLine(1, snapshotFrame.ciphertextBase64))

        val message = outputs.filterIsInstance<SessionOutput.Message>().single()
        val snapshot = Wire.decodeSnapshot(message.plaintext)
        assertEquals("req_abc123", snapshot?.prompt?.id)
        assertEquals(1, snapshot?.waiting)
    }

    @Test
    fun `turns away a host it has never been paired with`() {
        val session = PhoneSession(lookup = { null }, deviceName = DEVICE, phoneSalt = phoneSalt)
        val outputs = session.receive(handshake.hello.toByteArray())

        assertEquals(ByeReason.UNKNOWN_HOST, closeReason(outputs))
        assertEquals(PhoneSession.State.CLOSED, session.state)
        // A reason goes out before the door shuts, so the bridge is not left guessing.
        assertEquals(ByeReason.UNKNOWN_HOST, byeReason(outputs))
    }

    @Test
    fun `turns away a second host while one is being served`() {
        val session = newSession(busy = true)
        val outputs = session.receive(handshake.hello.toByteArray())

        assertEquals(ByeReason.BUSY, closeReason(outputs))
        assertEquals(PhoneSession.State.CLOSED, session.state)
    }

    @Test
    fun `refuses a hello from another protocol version`() {
        val session = newSession()
        val hello = """{"t":"hello","v":99,"host":"${handshake.hostId}","hs":"${handshake.hostSaltBase64}"}"""

        val outputs = session.receive(hello.toByteArray())

        assertEquals(ByeReason.VERSION, closeReason(outputs))
    }

    @Test
    fun `closes on a frame it cannot open`() {
        val session = newSession()
        session.receive(handshake.hello.toByteArray())

        val garbage = Base64.getEncoder().encodeToString(ByteArray(40) { 0x7A })
        val outputs = session.receive(frameLine(0, garbage))

        assertEquals(ByeReason.BAD_FRAME, closeReason(outputs))
        assertEquals(PhoneSession.State.CLOSED, session.state)
    }

    @Test
    fun `refuses to seal before the handshake finishes`() {
        val session = newSession()
        assertNull(session.seal("{}".toByteArray()))
        session.receive(handshake.hello.toByteArray())
        assertNull(session.seal("{}".toByteArray()))
    }

    @Test
    fun `stops talking once closed`() {
        val session = newSession(busy = true)
        session.receive(handshake.hello.toByteArray())

        assertTrue(session.receive(handshake.hello.toByteArray()).isEmpty())
        assertNull(session.seal("{}".toByteArray()))
    }

    // MARK: - Helpers

    private fun newSession(busy: Boolean = false) = PhoneSession(
        lookup = { id ->
            if (id == handshake.hostId) {
                PairedHost(handshake.hostId, psk, handshake.hostName)
            } else {
                null
            }
        },
        deviceName = DEVICE,
        phoneSalt = phoneSalt,
        isBusy = { busy },
    )

    private fun readySession(): PhoneSession = newSession().also {
        it.receive(handshake.hello.toByteArray())
        it.receive(frameLine(0, handshake.authCiphertextBase64))
    }

    private fun sessionKeys() = SessionKeys.derive(psk, hostSalt, phoneSalt)

    private fun frameLine(counter: Long, ciphertext: String): ByteArray =
        (json.encodeToString(Frame(counter, ciphertext)) + "\n").toByteArray()

    private fun closeReason(outputs: List<SessionOutput>): String? =
        outputs.filterIsInstance<SessionOutput.Close>().singleOrNull()?.reason

    private fun byeReason(outputs: List<SessionOutput>): String? =
        outputs.filterIsInstance<SessionOutput.Send>().singleOrNull()?.let {
            json.decodeFromString<Bye>(String(it.line).trim()).reason
        }

    private val psk: ByteArray get() = vectors.keys.pskHex.hexToBytes()
    private val hostSalt: ByteArray get() = Base64.getDecoder().decode(handshake.hostSaltBase64)
    private val phoneSalt: ByteArray get() = Base64.getDecoder().decode(handshake.phoneSaltBase64)

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val DEVICE = "Pixel 7 Pro"

        val json = Json { ignoreUnknownKeys = true }

        val vectors: VectorsWithHandshake by lazy {
            var directory: File? = File(System.getProperty("user.dir") ?: ".")
            while (directory != null) {
                val candidate = File(directory, "docs/protocol/fixtures/vectors.json")
                if (candidate.isFile) return@lazy json.decodeFromString(candidate.readText())
                directory = directory.parentFile
            }
            error("cannot find docs/protocol/fixtures/vectors.json")
        }

        val handshake: HandshakeVector get() = vectors.handshake
    }
}

@Serializable
data class VectorsWithHandshake(
    val keys: VectorKeys,
    val frames: List<VectorFrame>,
    val handshake: HandshakeVector,
)

@Serializable
data class HandshakeVector(
    val hostId: String,
    val hostName: String,
    val deviceName: String,
    val hostSaltBase64: String,
    val phoneSaltBase64: String,
    val hello: String,
    val challenge: String,
    val authCiphertextBase64: String,
    val readyCiphertextBase64: String,
    val authTime: List<Long>,
)
