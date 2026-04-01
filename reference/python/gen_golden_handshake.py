"""Deterministic handshake for Kotlin golden tests (same construction as random version)."""
import hashlib
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SKIP_LEN = 8
PREKEY_LEN = 32
IV_LEN = 16
PROTO_TAG_POS = 56
DC_IDX_POS = 60
PROTO_TAG_ABRIDGED = b"\xef\xef\xef\xef"

RESERVED_FIRST_BYTES = {0xEF}
RESERVED_STARTS = {
    b"HEAD",
    b"POST",
    b"GET ",
    b"\xee\xee\xee\xee",
    b"\xdd\xdd\xdd\xdd",
    b"\x16\x03\x01\x02",
}
RESERVED_CONTINUE = b"\x00\x00\x00\x00"

secret = bytes.fromhex("00112233445566778899aabbccddeeff")

# Fixed inputs (index i=0 from brute search) so output is stable across runs.
_seed = (0).to_bytes(8, "big")
dec_prekey = hashlib.sha256(_seed).digest()
dec_iv = hashlib.sha256(dec_prekey).digest()[:16]
_tail2 = hashlib.sha256(dec_prekey + b"t").digest()[:2]


def build_handshake() -> bytes:
    dec_key = hashlib.sha256(dec_prekey + secret).digest()
    dec_iv_int = int.from_bytes(dec_iv, "big")
    enc = Cipher(
        algorithms.AES(dec_key),
        modes.CTR(dec_iv_int.to_bytes(16, "big")),
    ).encryptor()
    ks = enc.update(b"\x00" * 64)

    p = bytearray(64)
    c_mid = dec_prekey + dec_iv
    for i in range(48):
        p[SKIP_LEN + i] = ks[SKIP_LEN + i] ^ c_mid[i]

    p[PROTO_TAG_POS : PROTO_TAG_POS + 4] = PROTO_TAG_ABRIDGED
    struct.pack_into("<h", p, DC_IDX_POS, 2)
    p[62:64] = _tail2

    c = bytearray(64)
    for i in range(64):
        c[i] = p[i] ^ ks[i]

    assert c[0] not in RESERVED_FIRST_BYTES
    assert bytes(c[:4]) not in RESERVED_STARTS
    assert bytes(c[4:8]) != RESERVED_CONTINUE
    return bytes(c)


c = build_handshake()
print("SECRET=" + secret.hex())
print("HANDSHAKE=" + c.hex())
