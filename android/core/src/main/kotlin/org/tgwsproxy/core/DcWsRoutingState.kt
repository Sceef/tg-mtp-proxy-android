package org.tgwsproxy.core

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

typealias DcMediaKey = Pair<Int, Boolean>

/**
 * Process-wide WS blacklist and per-DC cooldown (matches Python ws_blacklist / dc_fail_until).
 */
object DcWsRoutingState {
    private val blacklist = Collections.newSetFromMap(ConcurrentHashMap<DcMediaKey, Boolean>())
    private val failUntilSec = ConcurrentHashMap<DcMediaKey, Double>()

    fun isWsBlacklisted(dc: Int, isMedia: Boolean): Boolean =
        blacklist.contains(dc to isMedia)

    fun blacklistWs(dc: Int, isMedia: Boolean) {
        blacklist.add(dc to isMedia)
    }

    fun failCooldownUntilSec(key: DcMediaKey): Double? = failUntilSec[key]

    fun setFailCooldownUntilSec(key: DcMediaKey, monotonicSec: Double) {
        failUntilSec[key] = monotonicSec
    }

    fun clearFailCooldown(key: DcMediaKey) {
        failUntilSec.remove(key)
    }
}
