package org.tgwsproxy.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HandshakeResult(
    val dc: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val clientDecPrekeyIv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeResult) return false
        return dc == other.dc && isMedia == other.isMedia &&
            protoTag.contentEquals(other.protoTag) &&
            clientDecPrekeyIv.contentEquals(other.clientDecPrekeyIv)
    }

    override fun hashCode(): Int {
        var result = dc
        result = 31 * result + isMedia.hashCode()
        result = 31 * result + protoTag.contentHashCode()
        result = 31 * result + clientDecPrekeyIv.contentHashCode()
        return result
    }
}

fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
    require(handshake.size == ProtocolConstants.HANDSHAKE_LEN) { "handshake len" }
    val decPrekeyAndIv = handshake.copyOfRange(
        ProtocolConstants.SKIP_LEN,
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN,
    )
    val decPrekey = decPrekeyAndIv.copyOfRange(0, ProtocolConstants.PREKEY_LEN)
    val decIv = decPrekeyAndIv.copyOfRange(ProtocolConstants.PREKEY_LEN, ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN)
    val decKey = MessageDigest.getInstance("SHA-256").run {
        update(decPrekey)
        update(secret)
        digest()
    }
    val decryptor = AesCtrStream(decKey, decIv, normalizeCounter = true)
    val decrypted = decryptor.update(handshake)

    val protoTag = decrypted.copyOfRange(ProtocolConstants.PROTO_TAG_POS, ProtocolConstants.PROTO_TAG_POS + 4)
    if (!protoTag.contentEquals(ProtocolConstants.PROTO_TAG_ABRIDGED) &&
        !protoTag.contentEquals(ProtocolConstants.PROTO_TAG_INTERMEDIATE) &&
        !protoTag.contentEquals(ProtocolConstants.PROTO_TAG_SECURE)
    ) {
        return null
    }

    val dcIdx = ByteBuffer.wrap(decrypted, ProtocolConstants.DC_IDX_POS, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    val dcId = kotlin.math.abs(dcIdx)
    val isMedia = dcIdx < 0
    return HandshakeResult(dcId, isMedia, protoTag, decPrekeyAndIv)
}

fun interface RandomBytes {
    fun nextBytes(size: Int): ByteArray
}

fun generateRelayInit(protoTag: ByteArray, dcIdx: Int, random: RandomBytes = RandomBytes { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } }): ByteArray {
    val rnd = ByteArray(ProtocolConstants.HANDSHAKE_LEN)
    while (true) {
        random.nextBytes(ProtocolConstants.HANDSHAKE_LEN).copyInto(destination = rnd)
        val b0 = rnd[0].toInt() and 0xFF
        if (b0 in ProtocolConstants.RESERVED_FIRST_BYTES) continue
        if (ProtocolConstants.RESERVED_STARTS.any { rs -> rnd.copyOfRange(0, 4).contentEquals(rs) }) continue
        if (rnd.copyOfRange(4, 8).contentEquals(ProtocolConstants.RESERVED_CONTINUE)) continue
        break
    }

    val encKey = rnd.copyOfRange(ProtocolConstants.SKIP_LEN, ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN)
    val encIv = rnd.copyOfRange(
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN,
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN,
    )
    val encryptor = AesCtrStream(encKey, encIv)
    val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
    val tailPlain = protoTag + dcBytes + random.nextBytes(2)
    val encryptedFull = encryptor.update(rnd)
    val keystreamTail = ByteArray(8) { i ->
        (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
    }
    val encryptedTail = ByteArray(8) { i ->
        (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
    }
    val result = rnd.copyOf()
    System.arraycopy(encryptedTail, 0, result, ProtocolConstants.PROTO_TAG_POS, 8)
    return result
}

/** Session ciphers after successful handshake (matches Python _handle_client). */
data class SessionCiphers(
    val cltDecrypt: AesCtrStream,
    val cltEncrypt: AesCtrStream,
    val tgEncrypt: AesCtrStream,
    val tgDecrypt: AesCtrStream,
)

fun buildSessionCiphers(
    secret: ByteArray,
    clientDecPrekeyIv: ByteArray,
    relayInit: ByteArray,
): SessionCiphers {
    val md = MessageDigest.getInstance("SHA-256")
    val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, ProtocolConstants.PREKEY_LEN)
    val cltDecIv = clientDecPrekeyIv.copyOfRange(ProtocolConstants.PREKEY_LEN, ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN)
    val cltDecKey = md.run { reset(); update(cltDecPrekey); update(secret); digest() }

    val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
    val cltEncKey = md.run {
        reset()
        update(cltEncPrekeyIv.copyOfRange(0, ProtocolConstants.PREKEY_LEN))
        update(secret)
        digest()
    }
    val cltEncIv = cltEncPrekeyIv.copyOfRange(ProtocolConstants.PREKEY_LEN, ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN)

    val cltDecrypt = AesCtrStream(cltDecKey, cltDecIv, normalizeCounter = false)
    val cltEncrypt = AesCtrStream(cltEncKey, cltEncIv, normalizeCounter = false)
    cltDecrypt.update(ProtocolConstants.ZERO_64)

    val relayEncKey = relayInit.copyOfRange(ProtocolConstants.SKIP_LEN, ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN)
    val relayEncIv = relayInit.copyOfRange(
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN,
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN,
    )
    val relayDecPrekeyIv = relayInit.copyOfRange(
        ProtocolConstants.SKIP_LEN,
        ProtocolConstants.SKIP_LEN + ProtocolConstants.PREKEY_LEN + ProtocolConstants.IV_LEN,
    ).reversedArray()
    val relayDecKey = relayDecPrekeyIv.copyOfRange(0, ProtocolConstants.KEY_LEN)
    val relayDecIv = relayDecPrekeyIv.copyOfRange(ProtocolConstants.KEY_LEN, ProtocolConstants.KEY_LEN + ProtocolConstants.IV_LEN)

    val tgEncrypt = AesCtrStream(relayEncKey, relayEncIv, normalizeCounter = false)
    val tgDecrypt = AesCtrStream(relayDecKey, relayDecIv, normalizeCounter = false)
    tgEncrypt.update(ProtocolConstants.ZERO_64)

    return SessionCiphers(cltDecrypt, cltEncrypt, tgEncrypt, tgDecrypt)
}
