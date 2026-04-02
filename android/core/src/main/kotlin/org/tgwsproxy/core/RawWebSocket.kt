package org.tgwsproxy.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val socketOut: OutputStream,
    private val maskRandom: SecureRandom,
) {
    @Volatile
    private var closed: Boolean = false

    suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        if (closed) throw ConnectionError("WebSocket closed")
        val frame = buildFrameMasked(OP_BINARY, data)
        socketOut.write(frame)
        socketOut.flush()
    }

    suspend fun sendBatch(parts: List<ByteArray>) = withContext(Dispatchers.IO) {
        if (closed) throw ConnectionError("WebSocket closed")
        var cap = 0
        for (p in parts) {
            cap += frameWireSize(p.size)
        }
        val bos = ByteArrayOutputStream(cap.coerceAtLeast(32))
        for (p in parts) {
            bos.write(buildFrameMasked(OP_BINARY, p))
        }
        socketOut.write(bos.toByteArray())
        socketOut.flush()
    }

    suspend fun recv(): ByteArray? = withContext(Dispatchers.IO) {
        while (!closed) {
            val (opcode, payload) = readFrame()
            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    try {
                        writeControlFrameAndFlush(OP_CLOSE, payload.copyOf(minOf(2, payload.size)))
                    } catch (_: Exception) { }
                    null
                }
                OP_PING -> {
                    try {
                        writeControlFrameAndFlush(OP_PONG, payload)
                    } catch (_: Exception) { }
                    continue
                }
                OP_PONG -> continue
                0x1, OP_BINARY -> return@withContext payload
                else -> continue
            }
        }
        null
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        closed = true
        try {
            writeControlFrameAndFlush(OP_CLOSE, ByteArray(0))
        } catch (_: Exception) { }
        try {
            socket.close()
        } catch (_: Exception) { }
    }

    fun isClosed(): Boolean = closed || socket.isClosed

    private fun writeControlFrameAndFlush(opcode: Int, data: ByteArray) {
        socketOut.write(buildFrameMasked(opcode, data))
        socketOut.flush()
    }

    private fun buildFrameMasked(opcode: Int, data: ByteArray): ByteArray {
        val length = data.size
        val fb = 0x80 or opcode
        val maskKey = ByteArray(4).also { maskRandom.nextBytes(it) }
        val masked = xorMask(data, maskKey)
        val bb = when {
            length < 126 -> {
                val b = ByteBuffer.allocate(2 + 4 + masked.size)
                b.put(fb.toByte())
                b.put((0x80 or length).toByte())
                b.put(maskKey)
                b.put(masked)
                b.array()
            }
            length < 65536 -> {
                val b = ByteBuffer.allocate(2 + 2 + 4 + masked.size)
                b.put(fb.toByte())
                b.put((0x80 or 126).toByte())
                b.order(ByteOrder.BIG_ENDIAN).putShort(length.toShort())
                b.put(maskKey)
                b.put(masked)
                b.array()
            }
            else -> {
                val b = ByteBuffer.allocate(2 + 8 + 4 + masked.size)
                b.put(fb.toByte())
                b.put((0x80 or 127).toByte())
                b.order(ByteOrder.BIG_ENDIAN).putLong(length.toLong())
                b.put(maskKey)
                b.put(masked)
                b.array()
            }
        }
        return bb
    }

    companion object {
        const val OP_BINARY: Int = 0x2
        const val OP_CLOSE: Int = 0x8
        const val OP_PING: Int = 0x9
        const val OP_PONG: Int = 0xA

        /** Wire size of one masked client frame with payload of [payloadLen] bytes. */
        private fun frameWireSize(payloadLen: Int): Int {
            val ext = when {
                payloadLen < 126 -> 0
                payloadLen < 65536 -> 2
                else -> 8
            }
            return 2 + ext + 4 + payloadLen
        }

        suspend fun connect(
            ip: String,
            domain: String,
            path: String = "/apiws",
            timeoutMs: Int = 10_000,
            socketBufferSize: Int = ProxyConfig.DEFAULT_BUFFER_SIZE,
        ): RawWebSocket = withContext(Dispatchers.IO) {
            val factory = trustAllSslContext().socketFactory as SSLSocketFactory
            val socket = factory.createSocket() as SSLSocket
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(ip, 443), timeoutMs)
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(domain))
            socket.sslParameters = params
            socket.startHandshake()

            val handshakeRng = SecureRandom()
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { handshakeRng.nextBytes(it) })
            val req = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("Origin: https://web.telegram.org\r\n")
                append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ")
                append("AppleWebKit/537.36 (KHTML, like Gecko) ")
                append("Chrome/131.0.0.0 Safari/537.36\r\n")
                append("\r\n")
            }
            val rawOut = socket.getOutputStream()
            rawOut.write(req.toByteArray(Charsets.UTF_8))
            rawOut.flush()

            val inp = socket.getInputStream()
            val lines = ArrayList<String>()
            while (true) {
                val line = inp.readHttpLine() ?: break
                if (line.isEmpty()) break
                lines.add(line)
            }

            if (lines.isEmpty()) {
                socket.close()
                throw WsHandshakeError(0, "empty response")
            }

            val first = lines[0]
            val parts = first.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

            if (statusCode == 101) {
                socket.soTimeout = 0
                val buf = ProxyConfig.coerceBufferSize(socketBufferSize)
                try {
                    socket.sendBufferSize = buf
                    socket.receiveBufferSize = buf
                } catch (_: Exception) { }
                return@withContext RawWebSocket(socket, inp, rawOut, SecureRandom())
            }

            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val hl = lines[i]
                val c = hl.indexOf(':')
                if (c > 0) {
                    headers[hl.substring(0, c).trim().lowercase()] = hl.substring(c + 1).trim()
                }
            }
            socket.close()
            throw WsHandshakeError(statusCode, first, headers, headers["location"])
        }

        private fun trustAllSslContext(): SSLContext {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
            return ctx
        }

        fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            return out
        }
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val hdr = input.readNBytesCompat(2)
        val opcode = hdr[0].toInt() and 0x0F
        var length = hdr[1].toInt() and 0x7F
        if (length == 126) {
            length = ByteBuffer.wrap(input.readNBytesCompat(2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        } else if (length == 127) {
            length = ByteBuffer.wrap(input.readNBytesCompat(8)).order(ByteOrder.BIG_ENDIAN).long.toInt()
        }
        val payload = if (hdr[1].toInt() and 0x80 != 0) {
            val maskKey = input.readNBytesCompat(4)
            val p = input.readNBytesCompat(length)
            xorMask(p, maskKey)
        } else {
            input.readNBytesCompat(length)
        }
        return opcode to payload
    }
}

class ConnectionError(msg: String) : Exception(msg)

private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var o = 0
    while (o < n) {
        val r = read(buf, o, n - o)
        if (r <= 0) throw java.io.EOFException()
        o += r
    }
    return buf
}

private fun InputStream.readHttpLine(): String? {
    val baos = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b == -1) return null
        if (b == '\n'.code) break
        if (baos.size() < 8192) baos.write(b)
    }
    return baos.toString(Charsets.ISO_8859_1).trimEnd('\r')
}
