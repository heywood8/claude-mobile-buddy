package dev.heywood8.claudebuddy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The bridges this phone will talk to, and their pre-shared keys.
 *
 * The keys authorise approving shell commands on someone's workstation, so they are not left
 * sitting in plain preferences. They are sealed with an AES key that lives in the Android
 * Keystore and never leaves it — on a Pixel that means hardware-backed, and an attacker with
 * a copy of the preferences file has nothing to work with.
 *
 * Deliberately not androidx.security:security-crypto: that library has sat in alpha for years
 * and is now deprecated, and this is sixty lines of platform API.
 */
object Keyring {
    private const val PREFS = "keyring"
    private const val ENTRY = "hosts"
    private const val KEY_ALIAS = "cmb.keyring.v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Serializable
    private data class StoredHost(val hostId: String, val keyBase64: String, val name: String)

    fun hosts(context: Context): List<PairedHost> = read(context).map {
        PairedHost(
            hostId = it.hostId,
            key = Base64.getDecoder().decode(it.keyBase64),
            name = it.name,
        )
    }

    fun lookup(context: Context, hostId: String): PairedHost? =
        hosts(context).firstOrNull { it.hostId == hostId }

    /** Re-pairing an existing host replaces its key rather than adding a second entry. */
    fun add(context: Context, host: PairedHost) {
        val kept = read(context).filterNot { it.hostId == host.hostId }
        write(
            context,
            kept + StoredHost(
                hostId = host.hostId,
                keyBase64 = Base64.getEncoder().encodeToString(host.key),
                name = host.name,
            ),
        )
    }

    fun remove(context: Context, hostId: String) {
        write(context, read(context).filterNot { it.hostId == hostId })
    }

    // MARK: - Storage

    private fun read(context: Context): List<StoredHost> {
        val blob = prefs(context).getString(ENTRY, null) ?: return emptyList()
        return runCatching {
            val raw = Base64.getDecoder().decode(blob)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            val json = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
            Wire.json.decodeFromString<List<StoredHost>>(String(json, Charsets.UTF_8))
        }.getOrElse {
            // Unreadable means the keystore key is gone — a restore to another device, or the
            // user clearing credentials. The pairings are unrecoverable either way, so say so
            // by returning nothing rather than crashing on every connection.
            emptyList()
        }
    }

    private fun write(context: Context, hosts: List<StoredHost>) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(
            Wire.json.encodeToString(hosts).toByteArray(Charsets.UTF_8)
        )
        val blob = Base64.getEncoder().encodeToString(cipher.iv + sealed)
        prefs(context).edit().putString(ENTRY, blob).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Not requiring user authentication: the foreground service has to open the
                // keyring to answer a bridge while the screen is off. Unlocking is enforced
                // where it matters — on the approval itself, via setAuthenticationRequired.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
