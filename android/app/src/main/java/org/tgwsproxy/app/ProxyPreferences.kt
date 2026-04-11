package org.tgwsproxy.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val BUFFER_SIZE = intPreferencesKey("buffer_size")
    val POOL_SIZE = intPreferencesKey("pool_size")
    val FALLBACK_CFPROXY = booleanPreferencesKey("fallback_cfproxy")
    val CFPROXY_PRIORITY = booleanPreferencesKey("cfproxy_priority")
    val CFPROXY_USER_DOMAIN = stringPreferencesKey("cfproxy_user_domain")
    val CFPROXY_FETCH_REMOTE = booleanPreferencesKey("cfproxy_fetch_remote")
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

    suspend fun saveMain(host: String, port: Int, secretHex: String) {
        context.dataStore.edit { prefs ->
            prefs[ProxyPrefKeys.HOST] = host.trim()
            prefs[ProxyPrefKeys.PORT] = port
            prefs[ProxyPrefKeys.SECRET_HEX] = secretHex.trim().lowercase()
        }
    }

    suspend fun saveAdvanced(
        dcLines: String,
        bufferSize: Int,
        poolSize: Int,
        fallbackCfproxy: Boolean,
        cfproxyPriority: Boolean,
        cfproxyUserDomain: String,
        cfproxyFetchRemote: Boolean,
    ) {
        context.dataStore.edit { prefs ->
            prefs[ProxyPrefKeys.DC_LINES] = dcLines.trimEnd()
            prefs[ProxyPrefKeys.BUFFER_SIZE] = ProxyConfig.coerceBufferSize(bufferSize)
            prefs[ProxyPrefKeys.POOL_SIZE] = ProxyConfig.coercePoolSize(poolSize)
            prefs[ProxyPrefKeys.FALLBACK_CFPROXY] = fallbackCfproxy
            prefs[ProxyPrefKeys.CFPROXY_PRIORITY] = cfproxyPriority
            prefs[ProxyPrefKeys.CFPROXY_USER_DOMAIN] = cfproxyUserDomain.trim()
            prefs[ProxyPrefKeys.CFPROXY_FETCH_REMOTE] = cfproxyFetchRemote
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
        val bufferStored = p[ProxyPrefKeys.BUFFER_SIZE]
        val poolStored = p[ProxyPrefKeys.POOL_SIZE]
        val bufferSize = if (bufferStored != null) {
            ProxyConfig.coerceBufferSize(bufferStored)
        } else {
            ProxyConfig.DEFAULT_BUFFER_SIZE
        }
        val poolSize = if (poolStored != null) {
            ProxyConfig.coercePoolSize(poolStored)
        } else {
            ProxyConfig.DEFAULT_POOL_SIZE
        }
        val fallbackCfproxy = p[ProxyPrefKeys.FALLBACK_CFPROXY] ?: true
        val cfproxyPriority = p[ProxyPrefKeys.CFPROXY_PRIORITY] ?: true
        val cfproxyUserDomain = p[ProxyPrefKeys.CFPROXY_USER_DOMAIN]?.trim().orEmpty()
        val cfproxyFetchRemote = p[ProxyPrefKeys.CFPROXY_FETCH_REMOTE] ?: true
        return ProxyConfig(
            port = port,
            host = host,
            secretHex = secret,
            dcRedirects = redirects,
            bufferSize = bufferSize,
            poolSize = poolSize,
            fallbackCfproxy = fallbackCfproxy,
            cfproxyPriority = cfproxyPriority,
            cfproxyUserDomain = cfproxyUserDomain,
            cfproxyFetchRemote = cfproxyFetchRemote,
        )
    }
}
