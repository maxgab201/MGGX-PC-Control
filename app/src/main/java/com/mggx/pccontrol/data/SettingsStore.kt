package com.mggx.pccontrol.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mggx.pccontrol.domain.DemoStatus
import com.mggx.pccontrol.domain.RelayConfig
import com.mggx.pccontrol.domain.RelayError
import com.mggx.pccontrol.domain.RelayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("mggx_settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    val demoMode: Boolean = false,
    val simulatedStatus: DemoStatus = DemoStatus.OFFLINE,
    val pcName: String = "MGGX PC",
    val relayUrl: String = "",
    val pcId: String = "main",
    val timeoutSeconds: Int = 8,
    val autoOpen: Boolean = false,
    val haptics: Boolean = true,
    val dynamicColors: Boolean = true,
)

sealed interface TokenReadResult {
    data object NotConfigured : TokenReadResult
    data class Available(val value: String) : TokenReadResult
    data object Unreadable : TokenReadResult
}
sealed interface TokenWriteResult { data object Saved : TokenWriteResult; data object Failed : TokenWriteResult }
data class SavedRelayConfig(val settings: AppSettings, val config: RelayConfig)

class SettingsStore(private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding"); val demo = booleanPreferencesKey("demo")
        val status = stringPreferencesKey("demo_status"); val name = stringPreferencesKey("pc_name")
        val url = stringPreferencesKey("relay_url"); val pcId = stringPreferencesKey("pc_id")
        val timeout = intPreferencesKey("timeout"); val autoOpen = booleanPreferencesKey("auto_open")
        val haptics = booleanPreferencesKey("haptics"); val dynamic = booleanPreferencesKey("dynamic")
    }
    private val tokens = SecureTokenStore(context)
    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> AppSettings(
        p[K.onboarding] ?: false, p[K.demo] ?: false,
        runCatching { DemoStatus.valueOf(p[K.status] ?: "OFFLINE") }.getOrDefault(DemoStatus.OFFLINE),
        p[K.name] ?: "MGGX PC", p[K.url] ?: "", p[K.pcId] ?: "main", p[K.timeout] ?: 8,
        p[K.autoOpen] ?: false, p[K.haptics] ?: true, p[K.dynamic] ?: true,
    ) }

    suspend fun completeOnboarding(demo: Boolean) { context.dataStore.edit { it[K.onboarding] = true; it[K.demo] = demo } }
    suspend fun setDemo(enabled: Boolean) { context.dataStore.edit { it[K.demo] = enabled } }
    suspend fun setDemoStatus(status: DemoStatus) { context.dataStore.edit { it[K.status] = status.name } }
    suspend fun setAutoOpen(enabled: Boolean) { context.dataStore.edit { it[K.autoOpen] = enabled } }

    /**
     * Persists and returns the exact configuration used by the next request. It never waits for
     * a Flow collector, preventing save/test from accidentally using the previous relay.
     */
    suspend fun saveRelayAndCreateConfig(url: String, tokenInput: String?, pcId: String, timeout: Int): RelayResult<SavedRelayConfig> {
        val endpoint = when (val normalized = RelayUrlNormalizer.normalize(url)) {
            is RelayResult.Success -> normalized.value
            is RelayResult.Failure -> return normalized
        }
        if (tokenInput != null) {
            when (tokens.write(tokenInput.trim())) {
                TokenWriteResult.Saved -> Unit
                TokenWriteResult.Failed -> return RelayResult.Failure(RelayError.TokenUnreadable)
            }
        }
        val token = when (val stored = tokens.read()) {
            is TokenReadResult.Available -> stored.value
            TokenReadResult.NotConfigured -> ""
            TokenReadResult.Unreadable -> return RelayResult.Failure(RelayError.TokenUnreadable)
        }
        val next = AppSettings(relayUrl = endpoint.displayUrl, pcId = pcId.trim().ifBlank { "main" }, timeoutSeconds = timeout.coerceIn(2, 60))
        context.dataStore.edit { p ->
            p[K.url] = next.relayUrl; p[K.pcId] = next.pcId; p[K.timeout] = next.timeoutSeconds
        }
        return RelayResult.Success(SavedRelayConfig(next, RelayConfig(next.relayUrl, token, next.pcId, next.timeoutSeconds)))
    }

    suspend fun loadRelayConfig(settings: AppSettings): RelayResult<RelayConfig> = when (val token = tokens.read()) {
        is TokenReadResult.Available -> RelayResult.Success(RelayConfig(settings.relayUrl, token.value, settings.pcId, settings.timeoutSeconds))
        TokenReadResult.NotConfigured -> RelayResult.Success(RelayConfig(settings.relayUrl, "", settings.pcId, settings.timeoutSeconds))
        TokenReadResult.Unreadable -> RelayResult.Failure(RelayError.TokenUnreadable)
    }

    suspend fun tokenConfigured(): Boolean = tokens.read() is TokenReadResult.Available
}

class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("mggx_secure", Context.MODE_PRIVATE)
    private val alias = "mggx_relay_token_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun write(token: String): TokenWriteResult = runCatching {
        if (token.isBlank()) { prefs.edit().remove("iv").remove("ciphertext").commit(); return TokenWriteResult.Saved }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val success = prefs.edit()
            .putString("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .putString("ciphertext", android.util.Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)).commit()
        check(success)
        TokenWriteResult.Saved
    }.getOrElse { TokenWriteResult.Failed }

    fun read(): TokenReadResult {
        val ivText = prefs.getString("iv", null)
        val encrypted = prefs.getString("ciphertext", null)
        if (ivText == null && encrypted == null) return TokenReadResult.NotConfigured
        if (ivText.isNullOrBlank() || encrypted.isNullOrBlank()) return TokenReadResult.Unreadable
        return runCatching {
            val iv = android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP)
            val data = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
            TokenReadResult.Available(String(Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }.doFinal(data), Charsets.UTF_8))
        }.getOrElse { TokenReadResult.Unreadable }
    }
}
