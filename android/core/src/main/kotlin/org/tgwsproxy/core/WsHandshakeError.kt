package org.tgwsproxy.core

class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}
