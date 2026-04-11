package org.tgwsproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Pool of CfProxy base domains (e.g. example.com for host kws{dc}.example.com).
 * Matches Flowseal/tg-ws-proxy: active domain first, optional refresh from GitHub.
 */
object CfProxyDomainStore {

    const val REMOTE_DOMAIN_LIST_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    val defaultDomains: List<String> = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
    )

    private val domainsRef = AtomicReference<List<String>>(defaultDomains)
    private val activeRef = AtomicReference<String>(defaultDomains.random())

    fun domainsSnapshot(): List<String> = domainsRef.get()

    fun activeDomain(): String = activeRef.get()

    fun setDomainsAndPickActive(domains: List<String>) {
        if (domains.isEmpty()) return
        val dedup = domains.distinct()
        domainsRef.set(dedup)
        val cur = activeRef.get()
        if (cur !in dedup) {
            activeRef.set(dedup.random())
        }
    }

    fun noteSuccessfulDomain(baseDomain: String) {
        if (baseDomain.isNotEmpty() && baseDomain != activeRef.get()) {
            activeRef.set(baseDomain)
        }
    }

    /**
     * Ordered base domains for tries: [active] + others (unique).
     */
    fun orderedBases(userDomain: String): List<String> {
        val u = userDomain.trim()
        if (u.isNotEmpty()) {
            return listOf(u)
        }
        val pool = domainsSnapshot()
        val active = activeDomain()
        val out = ArrayList<String>(pool.size)
        if (active in pool) out.add(active)
        for (d in pool) {
            if (d != active) out.add(d)
        }
        if (out.isEmpty()) out.addAll(defaultDomains)
        return out.distinct()
    }

    suspend fun refreshFromGitHubIfEnabled(config: ProxyConfig) {
        if (!config.cfproxyFetchRemote || config.cfproxyUserDomain.isNotBlank()) {
            return
        }
        withContext(Dispatchers.IO) {
            runCatching { fetchDomainListFromUrl(REMOTE_DOMAIN_LIST_URL) }
                .onSuccess { fetched ->
                    if (fetched.isNotEmpty()) {
                        setDomainsAndPickActive(fetched)
                    }
                }
        }
    }

    fun fetchDomainListFromUrl(urlString: String): List<String> {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "tg-mtp-proxy-android")
        }
        try {
            conn.inputStream.use { ins ->
                val text = ins.bufferedReader(Charsets.UTF_8).readText()
                return parseDomainLines(text)
            }
        } finally {
            conn.disconnect()
        }
    }

    fun parseDomainLines(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            out.add(s)
        }
        return out.toList()
    }
}
