package dev.heywood8.claudebuddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Nordic UART Service, as specified in docs/PROTOCOL.md. */
object Nus {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** The bridge writes here. */
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** We notify here. */
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    /** Client Characteristic Configuration, the standard subscribe switch. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

@Serializable
data class Prompt(
    val id: String,
    /** Which session is asking, so the question sits beside that session's own pet. */
    val session: String = "",
    val tool: String,
    val hint: String,
    /**
     * What the call is for, in Claude's own words — the line the terminal prints under the
     * command. Defaulted: most tools carry no such field, and a bridge older than this one
     * sends no snapshot that has it.
     */
    val why: String = "",
    val cwd: String = "",
    /** Wall-clock second at which the bridge gives up, so we can show a countdown. */
    val expires: Long = 0,
)

/**
 * One live Claude Code session.
 *
 * Times are absolute seconds in the host's clock, and [Snapshot.now] carries the host's own
 * clock alongside them — so durations are worked out entirely in the host's frame rather than
 * against this phone's, which is off by a second or two.
 */
@Serializable
data class SessionSummary(
    val id: String = "",
    /** Where it is working, which is what tells two sessions apart at a glance. */
    val cwd: String = "",
    val started: Long = 0,
    /** Last tool call seen from it. */
    val active: Long = 0,
    /** Last time you decided something for it. Zero if you never have. */
    val decided: Long = 0,
    /** Tokens the model has processed for it, as far as the transcript says. */
    val tokens: Long = 0,
    /**
     * When it last finished answering, in the host's clock. Zero while it is working.
     *
     * Nothing reports that you have read anything, so this is the closest signal there is:
     * the moment it stopped talking.
     */
    val finished: Long = 0,
    /** The last thing you asked it for, trimmed to a glance by the bridge. */
    val task: String = "",
)

/**
 * A decision taken somewhere other than this phone.
 *
 * Claude Code has no hook for the moment somebody answers the prompt in the terminal, so the
 * bridge infers it: a tool that ran was allowed. [how] is `allowed`, `denied`, or `gone` when
 * the request disappeared without saying which way.
 */
@Serializable
data class Resolution(
    val id: String = "",
    /** Whose request it was, so the right session's pet reacts. */
    val session: String = "",
    val how: String = "",
    /** Host clock, to be read against [Snapshot.now]. */
    val at: Long = 0,
)

/** Complete state, not a delta. */
@Serializable
data class Snapshot(
    val t: String = "snap",
    val total: Int = 0,
    val running: Int = 0,
    val waiting: Int = 0,
    val msg: String = "",
    val entries: List<String> = emptyList(),
    /** The head of the queue: what the notification is about. */
    val prompt: Prompt? = null,
    /** Everything queued behind it. The head is not repeated here. */
    val prompts: List<Prompt> = emptyList(),
    /** The host's clock when this was built. */
    val now: Long = 0,
    /** Defaulted, so a snapshot from a bridge without this field still decodes. */
    val sessions: List<SessionSummary> = emptyList(),
    /** Across every session the bridge knows about. */
    val tokens: Long = 0,
    /** Since local midnight on the host. */
    @SerialName("tokens_today")
    val tokensToday: Long = 0,
    /** The last decision taken anywhere but here. */
    val resolved: Resolution? = null,
) {
    /**
     * Everything waiting, head first.
     *
     * The wire keeps the head apart because the notification is about that one. On screen the
     * order matters for the same reason: each request is drawn beside the session that raised
     * it, but only the head is answerable, and the head is what the rail's buttons mean.
     */
    val pending: List<Prompt> get() = if (prompt == null) prompts else listOf(prompt) + prompts
}

@Serializable
enum class Verdict {
    @SerialName("once")
    ONCE,

    @SerialName("deny")
    DENY,
}

/** Field names follow Anthropic's maker specification. */
@Serializable
data class Decision(
    val cmd: String = "permission",
    val id: String,
    val decision: Verdict,
)

/**
 * A clipboard hand-off, in either direction.
 *
 * Symmetric on purpose, and carrying `t` rather than the maker specification's `cmd`: nothing
 * in that specification describes a clipboard, so there is no verb to follow, and inventing one
 * for this end alone would leave the two halves of a single feature looking unrelated.
 *
 * The text travels base64 rather than as a JSON string. JSON escaping expands a control
 * character to six bytes, so a clip of the wrong shape would push the sealed line past
 * [Wire.MAX_LINE] — which does not fail politely: [LineAssembler] drops the oversized line,
 * whatever follows the newline decrypts as garbage, and the session ends. Base64 is a flat four
 * thirds, so [TEXT_LIMIT] is provably under the cap rather than measured to be.
 */
@Serializable
data class Clip(
    val t: String = "clip",
    /** base64 of the UTF-8 text. */
    val b: String = "",
    /** The sender's own clock when it was copied. Display only. */
    val at: Long = 0,
) {
    /**
     * Null for a peer that sent something that is not base64, or not UTF-8 inside it.
     *
     * Decoded strictly, which `String(bytes, UTF_8)` is not: that substitutes U+FFFD for every
     * byte it cannot read and hands back a string, so a corrupted frame would arrive as a
     * clipboard full of replacement characters rather than as nothing. The bridge's
     * `String(data:encoding:)` refuses outright, and the two ends have to agree about what a
     * malformed clip means.
     */
    val text: String?
        get() = runCatching {
            val bytes = Base64.getDecoder().decode(b)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    companion object {
        /**
         * Bytes of UTF-8 text.
         *
         * Worked out backwards from the 8 KiB line cap: 22 bytes of frame envelope, a 16-byte
         * tag, base64 at four thirds and 35 bytes of this object's own JSON leave room for
         * 4554 bytes of text. 4096 keeps roughly 450 in hand.
         */
        const val TEXT_LIMIT = 4096

        fun of(text: String, at: Long): Clip =
            Clip(b = Base64.getEncoder().encodeToString(clamp(text)), at = at)

        /**
         * Cuts on a byte budget, backing off to a character boundary.
         *
         * Truncated silently and without an ellipsis, unlike `hint`: a clipboard is pasted
         * rather than read, and a marker glued to the end would be pasted too.
         */
        private fun clamp(text: String): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= TEXT_LIMIT) return bytes
            // A continuation byte is 10xxxxxx. Cutting on one splits a character, and the far
            // end decodes the whole clip to null rather than to something slightly short.
            var end = TEXT_LIMIT
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }
    }
}

/** Anything the bridge can send us. */
sealed interface Inbound {
    data class Snap(val snapshot: Snapshot) : Inbound

    data class Clipboard(val clip: Clip) : Inbound
}

object Wire {
    const val MAX_LINE = 8 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** A complete wire line, newline included. */
    fun encode(decision: Decision): ByteArray =
        (json.encodeToString(decision) + "\n").toByteArray(Charsets.UTF_8)

    /**
     * The JSON on its own, with no trailing newline.
     *
     * This is what goes inside an encrypted frame: the newline is framing for the outer
     * stream, and the frame envelope is already a line of its own.
     */
    fun encodePayload(decision: Decision): ByteArray =
        json.encodeToString(decision).toByteArray(Charsets.UTF_8)

    fun encodePayload(clip: Clip): ByteArray =
        json.encodeToString(clip).toByteArray(Charsets.UTF_8)

    /**
     * Returns null for anything we do not recognise. An unfamiliar line is not worth tearing
     * the link down for — the bridge may simply be newer than we are.
     *
     * Dispatched on `t` rather than by trying each type in turn: [Snapshot] defaults every
     * field, so it decodes happily from any object at all and would swallow every message
     * added after it.
     */
    fun decodeInbound(line: ByteArray): Inbound? {
        if (line.size > MAX_LINE) return null
        val text = String(line, Charsets.UTF_8)
        val tag = runCatching {
            json.parseToJsonElement(text).jsonObject["t"]?.jsonPrimitive?.content
        }.getOrNull()
        return when (tag) {
            "snap" -> runCatching { json.decodeFromString<Snapshot>(text) }
                .getOrNull()?.let(Inbound::Snap)

            "clip" -> runCatching { json.decodeFromString<Clip>(text) }
                .getOrNull()?.let(Inbound::Clipboard)

            else -> null
        }
    }

    /** The snapshot alone, for the golden-vector tests. */
    fun decodeSnapshot(line: ByteArray): Snapshot? =
        (decodeInbound(line) as? Inbound.Snap)?.snapshot
}

/**
 * Reassembles newline-delimited lines out of GATT writes, which arrive chopped at whatever
 * the negotiated MTU allows.
 */
class LineAssembler {
    private val buffer = StringBuilder()

    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer.append(String(chunk, Charsets.UTF_8))
        val lines = mutableListOf<ByteArray>()
        while (true) {
            val nl = buffer.indexOf("\n")
            if (nl < 0) break
            val line = buffer.substring(0, nl)
            buffer.delete(0, nl + 1)
            if (line.isNotEmpty()) lines += line.toByteArray(Charsets.UTF_8)
        }
        // A peer that never terminates a line must not be able to exhaust our memory.
        if (buffer.length > MAX_LINE_CHARS) buffer.setLength(0)
        return lines
    }

    private companion object {
        const val MAX_LINE_CHARS = Wire.MAX_LINE
    }
}
