package org.tgwsproxy.core

import java.net.DatagramSocket
import java.net.InetAddress

/** Match Python [get_link_host] for tg://proxy server= field. */
fun getLinkHost(bindHost: String): String {
    if (bindHost != "0.0.0.0") return bindHost
    return try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName("8.8.8.8"), 80)
            s.localAddress.hostAddress ?: "127.0.0.1"
        }
    } catch (_: Exception) {
        "127.0.0.1"
    }
}

fun buildTgProxyLink(host: String, port: Int, secretHex: String): String =
    "tg://proxy?server=$host&port=$port&secret=dd$secretHex"
