package org.tgwsproxy.core

object DcRouting {
    val defaultDcIps: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    fun wsDomains(dc: Int, isMedia: Boolean, overrides: Map<Int, Int>): List<String> {
        val d = overrides[dc] ?: dc
        return if (isMedia) {
            listOf("kws$d-1.web.telegram.org", "kws$d.web.telegram.org")
        } else {
            listOf("kws$d.web.telegram.org", "kws$d-1.web.telegram.org")
        }
    }

    fun fallbackIp(dc: Int): String? = defaultDcIps[dc]
}
