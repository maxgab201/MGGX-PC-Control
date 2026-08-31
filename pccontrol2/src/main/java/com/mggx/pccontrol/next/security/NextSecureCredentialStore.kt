package com.mggx.pccontrol.next.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface CredentialResult {
    data object Missing : CredentialResult
    data class Value(val value: String) : CredentialResult
    data object Unreadable : CredentialResult
}

/** Each credential has its own Keystore alias; no token is put in DataStore or logs. */
class NextSecureCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("mggx_pc_control2_credentials", Context.MODE_PRIVATE)

    fun write(name: String, value: String): Boolean = runCatching {
        require(name.matches(Regex("[a-z_]{2,40}")))
        if (value.isBlank()) return preferences.edit().remove("$name.iv").remove("$name.data").commit()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(name)) }
        preferences.edit()
            .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$name.data", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    fun read(name: String): CredentialResult {
        val iv = preferences.getString("$name.iv", null)
        val data = preferences.getString("$name.data", null)
        if (iv == null && data == null) return CredentialResult.Missing
        if (iv.isNullOrBlank() || data.isNullOrBlank()) return CredentialResult.Unreadable
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(name), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            CredentialResult.Value(String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8))
        }.getOrElse { CredentialResult.Unreadable }
    }

    private fun key(name: String): SecretKey {
        val alias = "mggx_pc_control2_${name}_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }
}
