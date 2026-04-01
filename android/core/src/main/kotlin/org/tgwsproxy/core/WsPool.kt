package org.tgwsproxy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private data class PoolKey(val dc: Int, val isMedia: Boolean)

private data class HeldWs(val ws: RawWebSocket, val createdNano: Long)

class WsPool(
    private val scope: CoroutineScope,
    private val poolSize: Int,
) {
    companion object {
        private const val MAX_AGE_SEC = 120.0
    }

    private val idle = ConcurrentHashMap<PoolKey, ArrayDeque<HeldWs>>()
    private val refilling = Collections.newSetFromMap(ConcurrentHashMap<PoolKey, Boolean>())

    fun warmup(config: ProxyConfig) {
        for ((dc, ip) in config.dcRedirects) {
            if (ip.isEmpty()) continue
            for (isMedia in listOf(false, true)) {
                val key = PoolKey(dc, isMedia)
                val domains = DcRouting.wsDomains(dc, isMedia, config.dcOverrides)
                scheduleRefill(key, ip, domains)
            }
        }
    }

    suspend fun get(
        dc: Int,
        isMedia: Boolean,
        targetIp: String,
        domains: List<String>,
        stats: Stats,
    ): RawWebSocket? {
        val key = PoolKey(dc, isMedia)
        val bucket = idle.getOrPut(key) { ArrayDeque() }
        val now = System.nanoTime()
        synchronized(bucket) {
            while (bucket.isNotEmpty()) {
                val (ws, created) = bucket.removeFirst()
                val ageSec = (now - created) / 1_000_000_000.0
                if (ageSec > MAX_AGE_SEC || ws.isClosed()) {
                    scope.launch(Dispatchers.IO) { runCatching { ws.close() } }
                    continue
                }
                stats.poolHits.incrementAndGet()
                scheduleRefill(key, targetIp, domains)
                return ws
            }
            stats.poolMisses.incrementAndGet()
            scheduleRefill(key, targetIp, domains)
            return null
        }
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        if (!refilling.add(key)) return
        scope.launch(Dispatchers.IO) {
            try {
                refill(key, targetIp, domains)
            } finally {
                refilling.remove(key)
            }
        }
    }

    private suspend fun refill(key: PoolKey, targetIp: String, domains: List<String>) {
        val bucket = idle.getOrPut(key) { ArrayDeque() }
        synchronized(bucket) {
            val needed = poolSize - bucket.size
            if (needed <= 0) return
            repeat(needed) {
                val ws = connectOne(targetIp, domains)
                if (ws != null) {
                    bucket.addLast(HeldWs(ws, System.nanoTime()))
                }
            }
        }
    }

    private suspend fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(targetIp, domain, timeoutMs = 8_000)
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }
}
