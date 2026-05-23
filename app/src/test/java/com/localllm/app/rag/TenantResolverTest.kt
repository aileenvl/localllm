package com.localllm.app.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class TenantResolverTest {

    @Test
    fun `X-Client-Id takes priority over User-Agent`() {
        assertEquals(
            "app-a",
            resolveTenantFromHeaders(clientId = "App-A", userAgent = "Other/1.0"),
        )
    }

    @Test
    fun `X-Client-Id is lowercased and trimmed`() {
        assertEquals(
            "app-a",
            resolveTenantFromHeaders(clientId = "  App-A  ", userAgent = null),
        )
    }

    @Test
    fun `User-Agent is used when X-Client-Id is absent`() {
        assertEquals(
            "pitwall/1.0",
            resolveTenantFromHeaders(clientId = null, userAgent = "Pitwall/1.0"),
        )
    }

    @Test
    fun `empty headers resolve to anonymous`() {
        assertEquals("anonymous", resolveTenantFromHeaders(null, null))
        assertEquals("anonymous", resolveTenantFromHeaders("", ""))
        assertEquals("anonymous", resolveTenantFromHeaders("  ", "  "))
    }

    @Test
    fun `Foo slash 1 dot 0 and foo slash 1 dot 0 collapse`() {
        assertEquals(
            "foo/1.0",
            resolveTenantFromHeaders("Foo/1.0", "Bar/2.0"),
        )
        assertEquals(
            "foo/1.0",
            resolveTenantFromHeaders("foo/1.0", "Bar/2.0"),
        )
    }
}
