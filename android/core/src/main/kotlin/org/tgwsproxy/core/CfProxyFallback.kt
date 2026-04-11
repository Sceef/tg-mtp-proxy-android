package org.tgwsproxy.core

import java.net.Socket

/**
 * After normal Telegram WS (IP + kws*.web.telegram.org) fails, try CfProxy WS and/or TCP
 * per [ProxyConfig.fallbackCfproxy] and [ProxyConfig.cfproxyPriority].
 */
suspend fun runFallbackAfterWsFailed(
    socket: Socket,
    relayInit: ByteArray,
    protoInt: Int,
    config: ProxyConfig,
    stats: Stats,
    dc: Int,
    ciphers: SessionCiphers,
    tcpFallbackHost: String,
    wsTimeoutMs: Int,
): Boolean {
    if (!config.fallbackCfproxy) {
        return tcpFallback(
            socket, relayInit, tcpFallbackHost, 443, stats,
            ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
            config.bufferSize,
        )
    }
    val cfTimeout = wsTimeoutMs.coerceIn(2_000, 10_000)
    return if (config.cfproxyPriority) {
        if (cfProxyConnectAndBridge(socket, relayInit, protoInt, config, stats, dc, ciphers, cfTimeout)) {
            true
        } else {
            tcpFallback(
                socket, relayInit, tcpFallbackHost, 443, stats,
                ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
                config.bufferSize,
            )
        }
    } else {
        if (tcpFallback(
                socket, relayInit, tcpFallbackHost, 443, stats,
                ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
                config.bufferSize,
            )
        ) {
            true
        } else {
            cfProxyConnectAndBridge(socket, relayInit, protoInt, config, stats, dc, ciphers, cfTimeout)
        }
    }
}

private suspend fun cfProxyConnectAndBridge(
    socket: Socket,
    relayInit: ByteArray,
    protoInt: Int,
    config: ProxyConfig,
    stats: Stats,
    dc: Int,
    ciphers: SessionCiphers,
    connectTimeoutMs: Int,
): Boolean {
    val d = config.dcOverrides[dc] ?: dc
    val bases = CfProxyDomainStore.orderedBases(config.cfproxyUserDomain)
    val splitter = try {
        MsgSplitter(relayInit, protoInt)
    } catch (_: Exception) {
        null
    }
    for (base in bases) {
        val host = "kws$d.$base"
        try {
            val ws = RawWebSocket.connect(
                host, host,
                timeoutMs = connectTimeoutMs,
                socketBufferSize = config.bufferSize,
            )
            CfProxyDomainStore.noteSuccessfulDomain(base)
            stats.connectionsCfproxy.incrementAndGet()
            socket.soTimeout = 0
            ws.send(relayInit)
            bridgeWsReencrypt(
                socket, ws,
                ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
                splitter, stats,
            )
            return true
        } catch (e: WsHandshakeError) {
            stats.wsErrors.incrementAndGet()
            if (e.isRedirect) continue
            continue
        } catch (_: Exception) {
            stats.wsErrors.incrementAndGet()
            continue
        }
    }
    return false
}
