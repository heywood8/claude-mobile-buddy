package dev.heywood8.claudebuddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
     * The wire keeps the head apart because the notification is about that one; on screen the
     * distinction does not exist, since each request is drawn beside the session that raised
     * it and there is no queue to be at the front of.
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

    /**
     * Returns null for anything we do not recognise. An unfamiliar line is not worth tearing
     * the link down for — the bridge may simply be newer than we are.
     */
    fun decodeSnapshot(line: ByteArray): Snapshot? {
        if (line.size > MAX_LINE) return null
        return runCatching { json.decodeFromString<Snapshot>(String(line, Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.t == "snap" }
    }
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
