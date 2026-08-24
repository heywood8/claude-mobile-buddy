package dev.heywood8.claudebuddy

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoException(message: String) : Exception(message)

/**
 * RFC 5869, extract and expand. The JDK only grew a KDF API in a version Android does not
 * have, and HMAC-SHA256 is all this needs.
 */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

/**
 * Keys for one connection. Derived fresh per session so the AES-GCM counter can start at zero
 * every time without ever reusing a nonce under the long-lived pre-shared key. Separate keys
 * per direction mean a frame cannot be reflected back at its sender.
 */
class SessionKeys(
    val session: ByteArray,
    val hostToPhone: ByteArray,
    val phoneToHost: ByteArray,
) {
    companion object {
        /**
         * Non-empty salts throughout: RFC 5869 lets an absent salt mean a string of zeros, and
         * two implementations can disagree about whether "empty" means absent.
         */
        private val DOMAIN = "cmb/v1".toByteArray()

        fun derive(psk: ByteArray, hostSalt: ByteArray, phoneSalt: ByteArray): SessionKeys {
            val session = Hkdf.derive(
                ikm = psk,
                salt = hostSalt + phoneSalt,
                info = "cmb/v1/session".toByteArray(),
                length = 32,
            )
            return SessionKeys(
                session = session,
                hostToPhone = Hkdf.derive(session, DOMAIN, "h2p".toByteArray(), 32),
                phoneToHost = Hkdf.derive(session, DOMAIN, "p2h".toByteArray(), 32),
            )
        }
    }
}

object Aead {
    const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8

    /** Twelve bytes: eight zeros then the counter, big-endian. */
    fun nonce(counter: Long): ByteArray = ByteArray(12).also {
        it[8] = (counter ushr 24).toByte()
        it[9] = (counter ushr 16).toByte()
        it[10] = (counter ushr 8).toByte()
        it[11] = counter.toByte()
    }

    /**
     * The counter is authenticated as well as carried in the nonce, so a frame renumbered in
     * flight fails to open rather than silently shifting the stream.
     */
    fun aad(counter: Long): ByteArray = counter.toString().toByteArray()

    /** Returns ciphertext followed by the tag, which is what the JDK already produces. */
    fun seal(plaintext: ByteArray, counter: Long, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(counter)),
        )
        cipher.updateAAD(aad(counter))
        return cipher.doFinal(plaintext)
    }

    fun open(blob: ByteArray, counter: Long, key: ByteArray): ByteArray {
        if (blob.size <= TAG_BYTES) throw CryptoException("frame shorter than its tag")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(counter)),
        )
        cipher.updateAAD(aad(counter))
        return try {
            cipher.doFinal(blob)
        } catch (e: Exception) {
            throw CryptoException("frame did not authenticate")
        }
    }
}

/** One line of the encrypted stream. */
@Serializable
data class Frame(val n: Long, val c: String)

/**
 * Applies the counter rules on top of [Aead].
 *
 * Counters are explicit in the frame rather than implied by arrival order, so a dropped
 * notification is detected instead of silently desynchronising the stream.
 */
class SessionChannel(private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var nextSend = 0L
    private var nextReceive = 0L

    fun seal(plaintext: ByteArray): ByteArray {
        if (nextSend > MAX_COUNTER) throw CryptoException("counter exhausted")
        val blob = Aead.seal(plaintext, nextSend, sendKey)
        val frame = Frame(nextSend, Base64.getEncoder().encodeToString(blob))
        nextSend++
        return (Wire.json.encodeToString(frame) + "\n").toByteArray()
    }

    fun open(line: ByteArray): ByteArray {
        val frame = Wire.json.decodeFromString<Frame>(String(line, Charsets.UTF_8))
        if (frame.n != nextReceive) {
            throw CryptoException("expected counter $nextReceive, got ${frame.n}")
        }
        val blob = Base64.getDecoder().decode(frame.c)
        val plaintext = Aead.open(blob, frame.n, receiveKey)
        nextReceive++
        return plaintext
    }

    private companion object {
        const val MAX_COUNTER = 0xFFFFFFFEL
    }
}
