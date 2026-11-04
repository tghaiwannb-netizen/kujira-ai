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
class SecureStore(context: Context, private val testKey: SecretKey? = null) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Legacy development builds had a plaintext base64 fallback. Evict such
    // entries immediately; callers must re-enter/re-authorize credentials.
    init {
        val unsafeKeys = prefs.all.filterValues { it is String && it.startsWith("plain:") }.keys
        if (unsafeKeys.isNotEmpty()) {
            prefs.edit().apply { unsafeKeys.forEach { remove(it) } }.commit()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        testKey?.let { return it }
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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
            prefs.edit().remove(key).apply()
            return
        }
        // Fail closed: do not store API keys or OAuth tokens when encryption fails.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = "enc:" + Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(key, blob).apply()
    }

    fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        if (encoded.startsWith("plain:")) {
            prefs.edit().remove(key).apply()
            return null
        }
        return runCatching {
            val blob = Base64.decode(encoded.removePrefix("enc:"), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

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
