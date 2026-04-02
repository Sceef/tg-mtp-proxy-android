package org.tgwsproxy.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.tgwsproxy.core.ProxyConfig
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proxy_settings")

object ProxyPrefKeys {
    val PORT = intPreferencesKey("port")
    val HOST = stringPreferencesKey("host")
    val SECRET_HEX = stringPreferencesKey("secret_hex")
    val DC_LINES = stringPreferencesKey("dc_lines")
}

class ProxyPreferencesRepository(private val context: Context) {

    val preferencesFlow: Flow<Preferences> = context.dataStore.data

    fun configFlow(): Flow<ProxyConfig> = context.dataStore.data.map { p -> preferencesToConfig(p) }

    suspend fun currentConfig(): ProxyConfig = preferencesToConfig(context.dataStore.data.first())

    suspend fun rawDcLines(): String =
        context.dataStore.data.first()[ProxyPrefKeys.DC_LINES]?.trim() ?: ""

    suspend fun ensureSecretIfEmpty(): String {
        val p = context.dataStore.data.first()
        val existing = p[ProxyPrefKeys.SECRET_HEX]
        if (!existing.isNullOrBlank() && existing.length == 32) {
            return existing
        }
        val rnd = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hex = rnd.joinToString("") { b -> "%02x".format(b) }
        context.dataStore.edit { it[ProxyPrefKeys.SECRET_HEX] = hex }
        return hex
    }

    suspend fun save(
        host: String,
        port: Int,
        secretHex: String,
        dcLines: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[ProxyPrefKeys.HOST] = host.trim()
            prefs[ProxyPrefKeys.PORT] = port
            prefs[ProxyPrefKeys.SECRET_HEX] = secretHex.trim().lowercase()
            prefs[ProxyPrefKeys.DC_LINES] = dcLines.trimEnd()
        }
    }

    private fun preferencesToConfig(p: Preferences): ProxyConfig {
        val port = p[ProxyPrefKeys.PORT] ?: 1443
        val host = p[ProxyPrefKeys.HOST]?.trim()?.ifEmpty { "127.0.0.1" } ?: "127.0.0.1"
        val secret = p[ProxyPrefKeys.SECRET_HEX]?.trim()?.lowercase()
            ?: "00112233445566778899aabbccddeeff"
        val dcLines = p[ProxyPrefKeys.DC_LINES]?.trim() ?: ""
        val redirects = if (dcLines.isEmpty()) {
            ProxyConfig.defaultDcRedirects
        } else {
            ProxyConfig.parseDcIpList(
                dcLines.lines().map { it.trim() }.filter { it.isNotEmpty() },
            )
        }
        return ProxyConfig(
            port = port,
            host = host,
            secretHex = secret,
            dcRedirects = redirects,
        )
    }
}
