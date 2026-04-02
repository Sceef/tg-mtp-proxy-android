package org.tgwsproxy.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

private fun monoSec(): Double = System.nanoTime() / 1_000_000_000.0

private fun drainSocket(socket: Socket) {
    try {
        socket.soTimeout = 500
        val buf = ByteArray(4096)
        val inp = socket.getInputStream()
        while (inp.read(buf) > 0) { }
    } catch (_: Exception) { }
}

suspend fun bridgeWsReencrypt(
    socket: Socket,
    ws: RawWebSocket,
    cltDecrypt: AesCtrStream,
    cltEncrypt: AesCtrStream,
    tgEncrypt: AesCtrStream,
    tgDecrypt: AesCtrStream,
    splitter: MsgSplitter?,
    stats: Stats,
) = coroutineScope {
    val input = socket.getInputStream()
    val output = socket.getOutputStream()

    val jUp = async(Dispatchers.IO) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) {
                    splitter?.flush()?.forEach { part ->
                        if (part.isNotEmpty()) runCatching { ws.send(part) }
                    }
                    break
                }
                stats.bytesUp.addAndGet(n.toLong())
                val plain = cltDecrypt.update(buf, 0, n)
                val enc = tgEncrypt.update(plain)
                val parts = if (splitter != null) splitter.split(enc) else listOf(enc)
                if (parts.isEmpty()) continue
                if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val jDown = async(Dispatchers.IO) {
        try {
            while (true) {
                val data = ws.recv() ?: break
                stats.bytesDown.addAndGet(data.size.toLong())
                val plain = tgDecrypt.update(data)
                val out = cltEncrypt.update(plain)
                output.write(out)
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            runCatching { output.flush() }
        }
    }

    try {
        select {
            jUp.onAwait { }
            jDown.onAwait { }
        }
    } finally {
        jUp.cancel()
        jDown.cancel()
        runCatching { jUp.await() }
        runCatching { jDown.await() }
        runCatching { ws.close() }
        runCatching { output.flush() }
        runCatching {
            socket.shutdownOutput()
            socket.close()
        }
    }
}

suspend fun bridgeTcpReencrypt(
    client: Socket,
    remote: Socket,
    stats: Stats,
    cltDecrypt: AesCtrStream,
    cltEncrypt: AesCtrStream,
    tgEncrypt: AesCtrStream,
    tgDecrypt: AesCtrStream,
) = coroutineScope {
    val cin = client.getInputStream()
    val cout = client.getOutputStream()
    val rin = remote.getInputStream()
    val rout = remote.getOutputStream()

    val up = async(Dispatchers.IO) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = cin.read(buf)
                if (n <= 0) break
                stats.bytesUp.addAndGet(n.toLong())
                val plain = cltDecrypt.update(buf, 0, n)
                val data = tgEncrypt.update(plain)
                rout.write(data)
                rout.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            runCatching { rout.flush() }
        }
    }

    val down = async(Dispatchers.IO) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = rin.read(buf)
                if (n <= 0) break
                stats.bytesDown.addAndGet(n.toLong())
                val plain = tgDecrypt.update(buf, 0, n)
                val data = cltEncrypt.update(plain)
                cout.write(data)
                cout.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            runCatching { cout.flush() }
        }
    }

    try {
        select {
            up.onAwait { }
            down.onAwait { }
        }
    } finally {
        up.cancel()
        down.cancel()
        runCatching { up.await() }
        runCatching { down.await() }
        runCatching { client.close() }
        runCatching { remote.close() }
    }
}

suspend fun tcpFallback(
    client: Socket,
    relayInit: ByteArray,
    fallbackHost: String,
    port: Int,
    stats: Stats,
    cltDecrypt: AesCtrStream,
    cltEncrypt: AesCtrStream,
    tgEncrypt: AesCtrStream,
    tgDecrypt: AesCtrStream,
    ioBufferSize: Int = ProxyConfig.DEFAULT_BUFFER_SIZE,
): Boolean {
    val remote = withContext(Dispatchers.IO) {
        try {
            Socket().apply {
                soTimeout = 10_000
                tcpNoDelay = true
                connect(InetSocketAddress(fallbackHost, port), 10_000)
                val sb = ProxyConfig.coerceBufferSize(ioBufferSize)
                try {
                    sendBufferSize = sb
                    receiveBufferSize = sb
                } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            null
        }
    } ?: return false

    stats.connectionsTcpFallback.incrementAndGet()
    withContext(Dispatchers.IO) {
        remote.getOutputStream().write(relayInit)
        remote.getOutputStream().flush()
    }
    bridgeTcpReencrypt(
        client, remote, stats,
        cltDecrypt, cltEncrypt, tgEncrypt, tgDecrypt,
    )
    return true
}

suspend fun handleMtProtoClient(
    socket: Socket,
    secret: ByteArray,
    config: ProxyConfig,
    stats: Stats,
    wsPool: WsPool,
    label: String,
) {
    stats.connectionsTotal.incrementAndGet()
    stats.connectionsActive.incrementAndGet()
    try {
        socket.tcpNoDelay = true
        try {
            socket.sendBufferSize = config.bufferSize
            socket.receiveBufferSize = config.bufferSize
        } catch (_: Exception) {
        }

        socket.soTimeout = 10_000
        val handshake = withContext(Dispatchers.IO) {
            val input = socket.getInputStream()
            val h = ByteArray(ProtocolConstants.HANDSHAKE_LEN)
            var o = 0
            while (o < ProtocolConstants.HANDSHAKE_LEN) {
                val r = input.read(h, o, ProtocolConstants.HANDSHAKE_LEN - o)
                if (r < 0) return@withContext null
                o += r
            }
            h
        }
        if (handshake == null) return

        val result = tryHandshake(handshake, secret) ?: run {
            stats.connectionsBad.incrementAndGet()
            drainSocket(socket)
            return
        }

        val protoInt = when {
            result.protoTag.contentEquals(ProtocolConstants.PROTO_TAG_ABRIDGED) ->
                ProtocolConstants.PROTO_ABRIDGED_INT
            result.protoTag.contentEquals(ProtocolConstants.PROTO_TAG_INTERMEDIATE) ->
                ProtocolConstants.PROTO_INTERMEDIATE_INT
            else -> ProtocolConstants.PROTO_PADDED_INTERMEDIATE_INT
        }
        val dcIdx = if (result.isMedia) -result.dc else result.dc

        val relayInit = generateRelayInit(result.protoTag, dcIdx)
        val ciphers = buildSessionCiphers(secret, result.clientDecPrekeyIv, relayInit)

        val dcKey = result.dc to result.isMedia
        val inConfig = config.dcRedirects.containsKey(result.dc)

        if (!inConfig || DcWsRoutingState.isWsBlacklisted(result.dc, result.isMedia)) {
            val fb = DcRouting.fallbackIp(result.dc)
            if (fb != null) {
                tcpFallback(
                    socket, relayInit, fb, 443, stats,
                    ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
                    config.bufferSize,
                )
            }
            return
        }

        val now = monoSec()
        val failUntil = DcWsRoutingState.failCooldownUntilSec(dcKey) ?: 0.0
        val wsTimeoutMs = if (now < failUntil) 2000 else 10_000

        val domains = DcRouting.wsDomains(result.dc, result.isMedia, config.dcOverrides)
        val target = config.dcRedirects[result.dc]!!

        var ws: RawWebSocket? = wsPool.get(result.dc, result.isMedia, target, domains, stats)
        var wsFailedRedirect = false
        var allRedirects = true

        if (ws == null) {
            for (domain in domains) {
                try {
                    ws = RawWebSocket.connect(
                        target, domain,
                        timeoutMs = wsTimeoutMs,
                        socketBufferSize = config.bufferSize,
                    )
                    allRedirects = false
                    break
                } catch (e: WsHandshakeError) {
                    stats.wsErrors.incrementAndGet()
                    if (e.isRedirect) {
                        wsFailedRedirect = true
                        continue
                    }
                    allRedirects = false
                    continue
                } catch (_: Exception) {
                    stats.wsErrors.incrementAndGet()
                    allRedirects = false
                    continue
                }
            }
        } else {
            allRedirects = false
        }

        if (ws == null) {
            if (wsFailedRedirect && allRedirects) {
                DcWsRoutingState.blacklistWs(result.dc, result.isMedia)
            } else {
                DcWsRoutingState.setFailCooldownUntilSec(
                    dcKey,
                    now + ProtocolConstants.DC_FAIL_COOLDOWN_SEC,
                )
            }
            val fb = DcRouting.fallbackIp(result.dc) ?: target
            tcpFallback(
                socket, relayInit, fb, 443, stats,
                ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
                config.bufferSize,
            )
            return
        }

        DcWsRoutingState.clearFailCooldown(dcKey)
        stats.connectionsWs.incrementAndGet()

        val splitter = try {
            MsgSplitter(relayInit, protoInt)
        } catch (_: Exception) {
            null
        }

        socket.soTimeout = 0
        ws.send(relayInit)
        bridgeWsReencrypt(
            socket, ws,
            ciphers.cltDecrypt, ciphers.cltEncrypt, ciphers.tgEncrypt, ciphers.tgDecrypt,
            splitter, stats,
        )
    } finally {
        stats.connectionsActive.decrementAndGet()
        runCatching { socket.close() }
    }
}

class MtProtoProxyServer(
    private val config: ProxyConfig,
    private val stats: Stats,
    private val wsPool: WsPool,
) {
    private var serverSocket: java.net.ServerSocket? = null

    @Volatile
    private var running: Boolean = false

    fun start(scope: CoroutineScope): Job {
        wsPool.warmup(config)
        return scope.launch(Dispatchers.IO) {
            val ss = java.net.ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(config.host, config.port))
            serverSocket = ss
            running = true
            while (scope.isActive && running) {
                val s = try {
                    ss.accept()
                } catch (_: SocketException) {
                    if (!running) break
                    continue
                } catch (_: Exception) {
                    if (!running) break
                    continue
                }
                val peer = s.remoteSocketAddress?.toString() ?: "?"
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        handleMtProtoClient(s, config.secretBytes, config, stats, wsPool, peer)
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
