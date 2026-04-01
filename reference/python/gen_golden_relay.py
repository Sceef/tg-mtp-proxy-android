"""Deterministic relay_init for golden test (fixed os.urandom)."""
import hashlib
import os
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# Fixed "random" stream chunks as used by _generate_relay_init (order of urandom calls)
_CHUNKS = [
    # first while loop: 64 bytes until valid (we precomputed valid chunk)
    bytes.fromhex(
        "0102030405060708090a0b0c0d0e0f10"
        "1112131415161718191a1b1c1d1e1f20"
        "2122232425262728292a2b2c2d2e2f30"
        "3132333435363738393a3b3c3d3e3f40"
    ),
    # tail_plain last 2 bytes: os.urandom(2)
    bytes.fromhex("abcd"),
]

_idx = 0


def _fixed_urandom(n: int) -> bytes:
    global _idx
    b = _CHUNKS[_idx]
    _idx += 1
    assert len(b) == n, (len(b), n)
    return b


HANDSHAKE_LEN = 64
SKIP_LEN = 8
PREKEY_LEN = 32
IV_LEN = 16
PROTO_TAG_POS = 56
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


def generate_relay_init(proto_tag: bytes, dc_idx: int) -> bytes:
    while True:
        rnd = bytearray(_fixed_urandom(HANDSHAKE_LEN))
        if rnd[0] in RESERVED_FIRST_BYTES:
            continue
        if bytes(rnd[:4]) in RESERVED_STARTS:
            continue
        if rnd[4:8] == RESERVED_CONTINUE:
            continue
        break

    rnd_bytes = bytes(rnd)
    enc_key = rnd_bytes[SKIP_LEN : SKIP_LEN + PREKEY_LEN]
    enc_iv = rnd_bytes[SKIP_LEN + PREKEY_LEN : SKIP_LEN + PREKEY_LEN + IV_LEN]
    encryptor = Cipher(algorithms.AES(enc_key), modes.CTR(enc_iv)).encryptor()
    dc_bytes = struct.pack("<h", dc_idx)
    tail_plain = proto_tag + dc_bytes + _fixed_urandom(2)
    encrypted_full = encryptor.update(rnd_bytes)
    keystream_tail = bytes(encrypted_full[i] ^ rnd_bytes[i] for i in range(56, 64))
    encrypted_tail = bytes(tail_plain[i] ^ keystream_tail[i] for i in range(8))
    result = bytearray(rnd_bytes)
    result[PROTO_TAG_POS:HANDSHAKE_LEN] = encrypted_tail
    return bytes(result)


os.urandom = _fixed_urandom
RELAY = generate_relay_init(PROTO_TAG_ABRIDGED, 2)
print("RELAY_INIT=" + RELAY.hex())
