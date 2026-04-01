package org.tgwsproxy.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits ciphertext stream into MTProto transport frames for separate WS frames.
 */
class MsgSplitter(relayInit: ByteArray, protoInt: Int) {
    private val dec: AesCtrStream
    private var proto: Int = protoInt
    private val cipherBuf = ArrayList<Byte>()
    private val plainBuf = ArrayList<Byte>()
    private var disabled: Boolean = false

    init {
        val key = relayInit.copyOfRange(8, 40)
        val iv = relayInit.copyOfRange(40, 56)
        dec = AesCtrStream(key, iv)
        dec.update(ProtocolConstants.ZERO_64)
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        for (b in chunk) cipherBuf.add(b)
        val u = dec.update(chunk)
        for (b in u) plainBuf.add(b)

        val parts = ArrayList<ByteArray>()
        while (cipherBuf.isNotEmpty()) {
            val packetLen = nextPacketLen() ?: break
            if (packetLen <= 0) {
                parts.add(cipherBuf.toByteArray())
                cipherBuf.clear()
                plainBuf.clear()
                disabled = true
                break
            }
            if (cipherBuf.size < packetLen) break
            parts.add(cipherBuf.take(packetLen).toByteArray())
            repeat(packetLen) { cipherBuf.removeAt(0) }
            repeat(packetLen) { plainBuf.removeAt(0) }
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(): Int? {
        if (plainBuf.isEmpty()) return null
        return when (proto) {
            ProtocolConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen()
            ProtocolConstants.PROTO_INTERMEDIATE_INT,
            ProtocolConstants.PROTO_PADDED_INTERMEDIATE_INT,
            -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun nextAbridgedLen(): Int? {
        val first = plainBuf[0].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size < 4) return null
            val p = ByteBuffer.wrap(plainBuf.toByteArray(), 1, 3).order(ByteOrder.LITTLE_ENDIAN)
            val v = (p.get().toInt() and 0xFF) or ((p.get().toInt() and 0xFF) shl 8) or ((p.get().toInt() and 0xFF) shl 16)
            payloadLen = v * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (plainBuf.size < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(): Int? {
        if (plainBuf.size < 4) return null
        val arr = plainBuf.toByteArray()
        val payloadLen = ByteBuffer.wrap(arr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (plainBuf.size < packetLen) return null
        return packetLen
    }
}
