package com.lionotter.recipes.data.local

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Provides Tink AEAD encryption/decryption for sensitive data stored in DataStore.
 * Replaces the deprecated EncryptedSharedPreferences from androidx.security:security-crypto.
 *
 * The Tink keyset is stored in SharedPreferences and protected by the Android Keystore.
 */
class TinkEncryption(context: Context) {

    private val aead: Aead

    init {
        try {
            AeadConfig.register()
            aead = AndroidKeysetManager.Builder()
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        } catch (e: java.security.GeneralSecurityException) {
            Log.e(TAG, "Failed to initialize Tink AEAD", e)
            throw IllegalStateException("Tink AEAD could not be initialized", e)
        }
    }

    /**
     * Encrypts a plaintext string, returning a Base64-encoded ciphertext.
     */
    fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64-encoded ciphertext, returning the original plaintext string.
     * Returns null if decryption fails (e.g. corrupted data or wrong key).
     */
    fun decrypt(ciphertext: String): String? {
        return try {
            val encrypted = Base64.decode(ciphertext, Base64.NO_WRAP)
            val plaintext = aead.decrypt(encrypted, ASSOCIATED_DATA)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt data", e)
            null
        }
    }

    companion object {
        private const val TAG = "TinkEncryption"
        private const val KEYSET_NAME = "tink_api_key_keyset"
        private const val PREF_FILE_NAME = "tink_api_key_keyset_prefs"
        private const val MASTER_KEY_URI = "android-keystore://tink_master_key"
        private val ASSOCIATED_DATA = "api_key".toByteArray(Charsets.UTF_8)
    }
}
