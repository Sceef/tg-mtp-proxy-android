package org.tgwsproxy.core

data class ProxyConfig(
    val port: Int = 1443,
    val host: String = "127.0.0.1",
    val secretHex: String,
    val dcRedirects: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    ),
    val dcOverrides: Map<Int, Int> = mapOf(203 to 2),
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 4,
) {
    val secretBytes: ByteArray
        get() {
            require(secretHex.length == 32) { "secret must be 32 hex chars" }
            return secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

    companion object {
        fun parseDcIpList(entries: List<String>): Map<Int, String> {
            val out = linkedMapOf<Int, String>()
            for (e in entries) {
                val idx = e.indexOf(':')
                require(idx > 0) { "Invalid dc-ip $e" }
                val dc = e.substring(0, idx).toInt()
                val ip = e.substring(idx + 1)
                out[dc] = ip
            }
            return out
        }
    }
}
