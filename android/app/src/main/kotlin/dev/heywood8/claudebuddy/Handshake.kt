package dev.heywood8.claudebuddy

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.util.Base64

/** Plaintext, carries no secrets: an opaque host id and a fresh salt. */
@Serializable
data class Hello(val t: String = "hello", val v: Int = 1, val host: String, val hs: String)

/** We answer only if we know the host and no other host is active. */
@Serializable
data class Challenge(val t: String = "challenge", val v: Int = 1, val ps: String)

/**
 * First encrypted frame from the bridge. Opening it is our proof that the bridge holds the
 * key; there is no separate challenge-response step.
 */
@Serializable
data class Auth(val t: String = "auth", val name: String, val time: List<Long>)

/** First encrypted frame from us, and the mirror of the same proof. */
@Serializable
data class Ready(val t: String = "ready", val device: String, val proto: Int = 1)

@Serializable
data class Bye(val t: String = "bye", val reason: String)

object ByeReason {
    const val UNKNOWN_HOST = "unknown_host"
    const val BUSY = "busy"
    const val VERSION = "version"
    const val BAD_FRAME = "bad_frame"
    const val BAD_COUNTER = "bad_counter"
    const val SHUTDOWN = "shutdown"
}

/**
 * A paired bridge. Several may be stored; only one is served at a time.
 *
 * [hostId] is opaque rather than a machine name because it travels in the plaintext hello.
 */
data class PairedHost(val hostId: String, val key: ByteArray, val name: String) {
    override fun equals(other: Any?): Boolean =
        other is PairedHost && other.hostId == hostId

    override fun hashCode(): Int = hostId.hashCode()
}

/**
 * Parses the `cmb://pair?...` payload the bridge renders as a QR code.
 *
 * Deliberately hand-rolled rather than using `android.net.Uri`, so the same code runs under
 * plain JVM unit tests against the shared protocol vectors.
 */
object PairingCode {
    const val SCHEME = "cmb://pair"

    fun parse(text: String): PairedHost? {
        if (!text.startsWith("$SCHEME?")) return null
        val query = text.substring(SCHEME.length + 1)
        val values = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq)
            val value = runCatching {
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrNull() ?: continue
            values.putIfAbsent(name, value)
        }

        val hostId = values["h"] ?: return null
        if (hostId.length != 32 || !hostId.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val key = values["k"]?.let(::decodeBase64Url) ?: return null
        if (key.size != 32) return null
        return PairedHost(hostId = hostId, key = key, name = values["n"].orEmpty())
    }

    private fun decodeBase64Url(text: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(text.trimEnd('='))
    }.getOrNull()
}
