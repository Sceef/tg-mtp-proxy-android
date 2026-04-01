package org.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CryptoGoldenTest {
    private val goldenSecretHex = "00112233445566778899aabbccddeeff"
    private val goldenSecret = goldenSecretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Byte-aligned with [reference/python/gen_golden_handshake.py] (deterministic). */
    private val goldenHandshakeHex =
        "87c8871a426f614aaf5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc" +
            "7ef0ca626bbb058dd443bb78e33b888b239ceb38cd46cb8e"

    /** Final relay_init; matches [reference/python/gen_golden_relay.py] output. */
    private val goldenRelayInitHex =
        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" +
            "2122232425262728292a2b2c2d2e2f30313233343536373832bc9585f1fbdab7"

    /** First os.urandom(64) inside that script (raw rnd before tail overwrite). */
    private val goldenRelayRnd64Hex =
        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" +
            "2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40"

    @Test
    fun tryHandshakeGolden() {
        val hs = goldenHandshakeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val r = tryHandshake(hs, goldenSecret)
        assertNotNull(r)
        assertEquals(2, r.dc)
        assertEquals(false, r.isMedia)
        assertContentEquals(ProtocolConstants.PROTO_TAG_ABRIDGED, r.protoTag)
    }

    @Test
    fun tryHandshakeWrongSecret() {
        val hs = goldenHandshakeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val wrong = ByteArray(16) { 0 }
        assertNull(tryHandshake(hs, wrong))
    }

    @Test
    fun generateRelayInitGolden() {
        val chunks = mutableListOf<ByteArray>()
        chunks.add(
            goldenRelayRnd64Hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
        chunks.add(byteArrayOf(0xab.toByte(), 0xcd.toByte()))
        var idx = 0
        val rnd = RandomBytes { n ->
            chunks[idx++].also { require(it.size == n) { "expected $n got ${it.size}" } }
        }
        val relay = generateRelayInit(ProtocolConstants.PROTO_TAG_ABRIDGED, 2, rnd)
        assertContentEquals(
            goldenRelayInitHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            relay,
        )
    }

    @Test
    fun sessionCipherRoundTrip() {
        val relay = goldenRelayInitHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val hs = goldenHandshakeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val res = tryHandshake(hs, goldenSecret)!!
        val c = buildSessionCiphers(goldenSecret, res.clientDecPrekeyIv, relay)
        val plain = "hello-mtproto".encodeToByteArray()
        val fromClientWire = c.cltEncrypt.update(plain)
        val mid = c.cltDecrypt.update(fromClientWire)
        assertContentEquals(plain, mid)
        val toTg = c.tgEncrypt.update(mid)
        val back = c.tgDecrypt.update(toTg)
        assertContentEquals(mid, back)
    }
}
