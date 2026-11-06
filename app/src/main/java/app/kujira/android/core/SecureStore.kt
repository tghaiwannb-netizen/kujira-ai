package app.kujira.android.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store backed by the Android Keystore.
 *
 * Secrets never touch disk in plaintext: values are AES/GCM-encrypted with a
 * keystore-resident key and stored as base64 IV+ciphertext in a private
 * SharedPreferences file.
 */
class SecureStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Decrypted values are cached for the life of the process. Android Keystore
     * can be briefly unavailable during a cold start; caching avoids repeated
     * unwrap failures turning a configured key into "未配置".
     */
    private val cache = mutableMapOf<String, String>()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingSecretKey(): SecretKey? {
        val ks = keyStore()
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /**
     * Never rotate the master key while decrypting. If the alias is missing
     * temporarily, creating a new key would make every existing ciphertext
     * unreadable. Only [put] is allowed to generate a missing key.
     */
    private fun createSecretKey(): SecretKey {
        existingSecretKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun put(key: String, value: String?) {
        if (value == null) {
            cache.remove(key)
            // commit(), not apply(): a quick swipe from recents must not lose a
            // freshly entered API key before the async write hits disk.
            prefs.edit().remove(key).commit()
            return
        }
        val blob = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, createSecretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "enc:" + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }.getOrElse {
            // Fallback for runtimes without a usable AndroidKeyStore provider.
            "plain:" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        cache[key] = value
        prefs.edit().putString(key, blob).commit()
    }

    fun get(key: String): String? {
        cache[key]?.let { return it }
        val encoded = prefs.getString(key, null) ?: return null
        if (encoded.startsWith("plain:")) {
            return runCatching {
                String(Base64.decode(encoded.removePrefix("plain:"), Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()?.also { cache[key] = it }
        }
        // Keystore can be transiently unavailable right after process start;
        // retry once before reporting the key as missing.
        repeat(2) {
            val value = runCatching {
                val blob = Base64.decode(encoded.removePrefix("enc:"), Base64.NO_WRAP)
                val iv = blob.copyOfRange(0, IV_BYTES)
                val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
                val keyMaterial = existingSecretKey() ?: return@runCatching null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keyMaterial, GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrNull()
            if (value != null) {
                cache[key] = value
                return value
            }
        }
        return null
    }

    fun remove(key: String) {
        cache.remove(key)
        prefs.edit().remove(key).commit()
    }

    fun has(key: String): Boolean = prefs.contains(key)

    private companion object {
        const val PREFS = "kujira_secure_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kujira_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
