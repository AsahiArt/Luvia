package tech.asahiart.luvia

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(deviceId: String, privateKeyOpenssh: String) {
        val plaintext = privateKeyOpenssh.encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)
        preferences.edit()
            .putString(ciphertextKey(deviceId), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(deviceId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(deviceId: String): String? {
        val ciphertext = preferences.getString(ciphertextKey(deviceId), null)?.decodeBase64() ?: return null
        val iv = preferences.getString(ivKey(deviceId), null)?.decodeBase64() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    fun delete(deviceId: String) {
        preferences.edit().remove(ciphertextKey(deviceId)).remove(ivKey(deviceId)).apply()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
    private fun ciphertextKey(deviceId: String) = "device.$deviceId.key"
    private fun ivKey(deviceId: String) = "device.$deviceId.iv"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tech.asahiart.luvia.device-key-encryption"
        const val PREFERENCES_NAME = "secure-device-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
