package org.tgwsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CfProxyDomainStoreTest {

    @Test
    fun parseDomainLines_skipsCommentsAndEmpty() {
        val text = """
            # comment
            first.example.com

            second.example.com
            # another
        """.trimIndent()
        assertEquals(
            listOf("first.example.com", "second.example.com"),
            CfProxyDomainStore.parseDomainLines(text),
        )
    }
}
