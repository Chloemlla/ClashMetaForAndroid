package com.github.kr328.clash.service.util

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts strings using Android Keystore-backed AES-GCM.
 *
 * Keys are stored in the hardware-backed Android Keystore and cannot be
 * extracted, even from a rooted device or ADB backup. Use this for
 * sensitive fields like subscription URLs and ageSecretKey.
 *
 * Must be initialized with [init] before first use (e.g., in Application.onCreate).
 */
object SecureStorage {

    private const val KEYSTORE_ALIAS = "cmfa_secure_storage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val GCM_IV_LENGTH = 12   // bytes

    private var initialized = false
    private var key: SecretKey? = null

    @RequiresApi(Build.VERSION_CODES.M)
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (initialized) return

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            key = (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            key = keyGenerator.generateKey()
        }

        initialized = true
    }

    fun encrypt(plaintext: String): String {
        check(initialized) { "SecureStorage not initialized" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Prepend IV to ciphertext
        return (iv + ciphertext).encodeBase64()
    }

    fun decrypt(encoded: String): String {
        check(initialized) { "SecureStorage not initialized" }
        val raw = encoded.decodeBase64()
        if (raw.size < GCM_IV_LENGTH) return ""
        val iv = raw.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = raw.copyOfRange(GCM_IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun ByteArray.encodeBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
}