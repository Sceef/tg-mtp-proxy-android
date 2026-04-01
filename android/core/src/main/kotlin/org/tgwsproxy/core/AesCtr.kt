package org.tgwsproxy.core

import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CTR matching Python [cryptography.hazmat] behavior.
 * @param normalizeCounter if true, IV is processed like _try_handshake: int.from_bytes(iv,'big').to_bytes(16,'big')
 */
class AesCtrStream(
    key: ByteArray,
    iv: ByteArray,
    normalizeCounter: Boolean = false,
) {
    private val ivBlock: ByteArray =
        if (normalizeCounter) ivFromHandshake(iv) else padCtrIv(iv)

    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBlock))
    }

    fun update(data: ByteArray): ByteArray = cipher.update(data) ?: ByteArray(0)

    fun update(data: ByteArray, offset: Int, len: Int): ByteArray =
        cipher.update(data, offset, len) ?: ByteArray(0)

    companion object {
        fun padCtrIv(iv: ByteArray): ByteArray {
            require(iv.size <= 16) { "IV too long" }
            val o = ByteArray(16)
            System.arraycopy(iv, 0, o, 16 - iv.size, iv.size)
            return o
        }

        /** Python: int.from_bytes(iv, 'big').to_bytes(16, 'big') for handshake decrypt. */
        fun ivFromHandshake(iv: ByteArray): ByteArray {
            require(iv.size == 16) { "handshake IV must be 16 bytes" }
            val n = BigInteger(1, iv)
            val raw = n.toByteArray()
            val out = ByteArray(16)
            val len = minOf(16, raw.size)
            System.arraycopy(raw, raw.size - len, out, 16 - len, len)
            return out
        }
    }
}
