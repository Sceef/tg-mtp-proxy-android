package org.tgwsproxy.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits ciphertext stream into MTProto transport frames for separate WS frames.
 * Uses a sliding window over parallel cipher/plain buffers (O(n) per packet), not
 * repeated [ArrayList.removeAt] from the front — that was O(n²) and broke large
 * uploads (voice, video notes) when intermediate frames are hundreds of KB+.
 */
class MsgSplitter(relayInit: ByteArray, protoInt: Int) {
    private val dec: AesCtrStream
    private var proto: Int = protoInt
    private var cipher = ByteArray(INITIAL_CAP)
    private var plain = ByteArray(INITIAL_CAP)
    private var head = 0
    private var size = 0
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

        ensureCapacity(size + chunk.size)
        System.arraycopy(chunk, 0, cipher, head + size, chunk.size)
        val u = dec.update(chunk)
        System.arraycopy(u, 0, plain, head + size, u.size)
        require(u.size == chunk.size) { "CTR stream length mismatch" }
        size += chunk.size

        val parts = ArrayList<ByteArray>()
        while (size > 0) {
            val packetLen = nextPacketLen() ?: break
            if (packetLen <= 0) {
                parts.add(cipher.copyOfRange(head, head + size))
                head = 0
                size = 0
                disabled = true
                break
            }
            if (size < packetLen) break
            parts.add(cipher.copyOfRange(head, head + packetLen))
            head += packetLen
            size -= packetLen
            maybeCompact()
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (size == 0) return emptyList()
        val tail = cipher.copyOfRange(head, head + size)
        head = 0
        size = 0
        return listOf(tail)
    }

    private fun ensureCapacity(need: Int) {
        if (head + need <= cipher.size) return
        compact()
        if (head + need <= cipher.size) return
        val newCap = maxOf(cipher.size * 2, need + (need shr 1))
        cipher = cipher.copyOf(newCap)
        plain = plain.copyOf(newCap)
    }

    private fun maybeCompact() {
        if (head >= COMPACT_THRESHOLD && head > cipher.size / 2) {
            compact()
        }
    }

    private fun compact() {
        if (head == 0) return
        if (size == 0) {
            head = 0
            return
        }
        System.arraycopy(cipher, head, cipher, 0, size)
        System.arraycopy(plain, head, plain, 0, size)
        head = 0
    }

    private fun nextPacketLen(): Int? {
        if (size == 0) return null
        return when (proto) {
            ProtocolConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen()
            ProtocolConstants.PROTO_INTERMEDIATE_INT,
            ProtocolConstants.PROTO_PADDED_INTERMEDIATE_INT,
            -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun nextAbridgedLen(): Int? {
        val first = plain[head].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (size < 4) return null
            val b0 = plain[head + 1].toInt() and 0xFF
            val b1 = plain[head + 2].toInt() and 0xFF
            val b2 = plain[head + 3].toInt() and 0xFF
            val v = b0 or (b1 shl 8) or (b2 shl 16)
            payloadLen = v * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (size < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(): Int? {
        if (size < 4) return null
        val payloadLen = ByteBuffer.wrap(plain, head, size).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (size < packetLen) return null
        return packetLen
    }

    companion object {
        private const val INITIAL_CAP = 65536
        private const val COMPACT_THRESHOLD = 262_144
    }
}
