package dev.heywood8.claudebuddy

import java.security.SecureRandom
import java.util.Base64

/** What a session wants the transport to do next. */
sealed interface SessionOutput {
    /** Write this line to the peer. */
    class Send(val line: ByteArray) : SessionOutput

    /** The handshake finished; application messages may flow. */
    data object Ready : SessionOutput

    /** A decrypted application line from the peer. */
    class Message(val plaintext: ByteArray) : SessionOutput

    /** Give up, for this reason. */
    data class Close(val reason: String) : SessionOutput
}

/**
 * The phone's half of the handshake and the encrypted stream, with the radio taken out.
 *
 * Lines in, lines out, no Android Bluetooth classes anywhere — which is what lets the whole
 * protocol be exercised in plain JVM tests. [GattPeripheral] stays a transport that knows
 * nothing about the protocol.
 *
 * There is no plaintext path past the handshake. A peer that cannot produce a frame we can
 * open gets nothing and is disconnected.
 */
class PhoneSession(
    private val lookup: (String) -> PairedHost?,
    private val deviceName: String,
    private val phoneSalt: ByteArray = randomSalt(),
    /** Another host is already being served; only one at a time. */
    private val isBusy: () -> Boolean = { false },
) {
    enum class State { AWAITING_HELLO, AWAITING_AUTH, READY, CLOSED }

    var state: State = State.AWAITING_HELLO
        private set

    /** Which paired bridge is on the other end, once it has said so. */
    var host: PairedHost? = null
        private set

    /** The host's clock offset from the `auth` frame, so its timestamps render correctly. */
    var hostUtcOffsetSeconds: Long = 0
        private set

    private var channel: SessionChannel? = null

    fun receive(line: ByteArray): List<SessionOutput> = when (state) {
        State.CLOSED -> emptyList()
        State.AWAITING_HELLO -> onHello(line)
        State.AWAITING_AUTH -> onAuth(line)
        State.READY -> onMessage(line)
    }

    /** Wraps an application line for the wire. Only valid once ready. */
    fun seal(plaintext: ByteArray): ByteArray? {
        if (state != State.READY) return null
        return sealRaw(plaintext)
    }

    // MARK: - Steps

    private fun onHello(line: ByteArray): List<SessionOutput> {
        val hello = runCatching {
            Wire.json.decodeFromString<Hello>(String(line, Charsets.UTF_8))
        }.getOrNull()
        if (hello == null || hello.t != "hello") return close(ByeReason.BAD_FRAME)
        if (hello.v != VERSION) return close(ByeReason.VERSION)

        // Unknown host and busy are answered the same way from the attacker's point of view:
        // a reason string and a closed connection, with no data behind it either way.
        val paired = lookup(hello.host) ?: return close(ByeReason.UNKNOWN_HOST)
        if (isBusy()) return close(ByeReason.BUSY)

        val hostSalt = runCatching { Base64.getDecoder().decode(hello.hs) }.getOrNull()
        if (hostSalt == null || hostSalt.size != SALT_BYTES) return close(ByeReason.BAD_FRAME)

        val keys = SessionKeys.derive(paired.key, hostSalt, phoneSalt)
        channel = SessionChannel(sendKey = keys.phoneToHost, receiveKey = keys.hostToPhone)
        host = paired
        state = State.AWAITING_AUTH

        val challenge = Challenge(ps = Base64.getEncoder().encodeToString(phoneSalt))
        return listOf(SessionOutput.Send(encodeLine(Wire.json.encodeToString(challenge))))
    }

    private fun onAuth(line: ByteArray): List<SessionOutput> {
        val plaintext = open(line) ?: return close(ByeReason.BAD_FRAME)
        val auth = runCatching {
            Wire.json.decodeFromString<Auth>(String(plaintext, Charsets.UTF_8))
        }.getOrNull()
        if (auth == null || auth.t != "auth") return close(ByeReason.BAD_FRAME)

        hostUtcOffsetSeconds = auth.time.getOrElse(1) { 0L }

        // Sealed before the state flips, because `ready` is itself the frame that proves we
        // hold the key — the public seal() refuses until the handshake is done.
        val ready = Ready(device = deviceName)
        val sealed = sealRaw(Wire.json.encodeToString(ready).toByteArray(Charsets.UTF_8))
            ?: return close(ByeReason.BAD_FRAME)
        state = State.READY
        return listOf(SessionOutput.Send(sealed), SessionOutput.Ready)
    }

    private fun onMessage(line: ByteArray): List<SessionOutput> {
        val plaintext = open(line) ?: return close(ByeReason.BAD_FRAME)
        return listOf(SessionOutput.Message(plaintext))
    }

    // MARK: - Internals

    private fun open(line: ByteArray): ByteArray? {
        // A frame that will not open is not a hiccup to skip past: either the key is wrong or
        // someone is editing the stream. Either way the session is over.
        val channel = channel ?: return null
        return runCatching { channel.open(line.stripNewline()) }.getOrNull()
    }

    private fun sealRaw(plaintext: ByteArray): ByteArray? =
        runCatching { channel?.seal(plaintext) }.getOrNull()

    private fun close(reason: String): List<SessionOutput> {
        state = State.CLOSED
        channel = null
        val bye = encodeLine(Wire.json.encodeToString(Bye(reason = reason)))
        return listOf(SessionOutput.Send(bye), SessionOutput.Close(reason))
    }

    private fun encodeLine(json: String): ByteArray = (json + "\n").toByteArray(Charsets.UTF_8)

    private fun ByteArray.stripNewline(): ByteArray =
        if (isNotEmpty() && last() == '\n'.code.toByte()) copyOf(size - 1) else this

    companion object {
        const val VERSION = 1
        const val SALT_BYTES = 32

        fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
    }
}
