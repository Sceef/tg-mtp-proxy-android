package org.tgwsproxy.core

object ProtocolConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED: ByteArray = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE: ByteArray = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE: ByteArray = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    const val PROTO_ABRIDGED_INT: Int = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT: Int = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT: Int = 0xDDDDDDDD.toInt()

    val ZERO_64: ByteArray = ByteArray(64)

    const val DC_FAIL_COOLDOWN_SEC: Double = 30.0
    const val WS_FAIL_TIMEOUT_SEC: Double = 2.0

    val RESERVED_FIRST_BYTES: Set<Int> = setOf(0xEF)
    val RESERVED_STARTS: Set<ByteArray> = setOf(
        "HEAD".toByteArray(),
        "POST".toByteArray(),
        "GET ".toByteArray(),
        byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte()),
        byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )
    val RESERVED_CONTINUE: ByteArray = byteArrayOf(0, 0, 0, 0)
}
