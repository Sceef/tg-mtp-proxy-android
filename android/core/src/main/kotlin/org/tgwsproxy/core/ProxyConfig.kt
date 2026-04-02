package org.tgwsproxy.core

data class ProxyConfig(
    val port: Int = 1443,
    val host: String = "127.0.0.1",
    val secretHex: String,
    val dcRedirects: Map<Int, String> = defaultDcRedirects,
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
        /**
         * All common DC ids get a WS attempt (TLS to [ip], SNI kws*.web.telegram.org).
         * DC 2/4 use 149.154.167.220 like Flowseal/tg-ws-proxy defaults; others use canonical DC IPs.
         */
        val defaultDcRedirects: Map<Int, String> = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.220",
            3 to "149.154.175.100",
            4 to "149.154.167.220",
            5 to "149.154.171.5",
            203 to "91.105.192.100",
        )

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
