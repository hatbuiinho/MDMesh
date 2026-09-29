package com.mdmesh.agent.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {
    private val matcher = DomainMatcher(listOf("example.com", "*.company.vn"))

    @Test fun `matches exact names and subdomains`() {
        assertTrue(matcher.contains("example.com"))
        assertTrue(matcher.contains("API.Example.Com."))
        assertTrue(matcher.contains("deep.api.company.vn"))
    }

    @Test fun `does not match a textual suffix without label boundary`() {
        assertFalse(matcher.contains("notexample.com"))
        assertFalse(matcher.contains("example.org"))
    }

    @Test fun `runtime mdm host bypasses an empty allowlist`() {
        val policy = WebFilterPolicy("ALLOWLIST", emptySet(), setOf("mdm.customer.example"))
        assertFalse(policy.blocks("mdm.customer.example"))
        assertFalse(policy.blocks("push.mdm.customer.example"))
        assertTrue(policy.blocks("unrelated.example"))
    }

    @Test fun `runtime mdm host bypasses a matching parent block rule`() {
        val policy = WebFilterPolicy("BLOCKLIST", setOf("customer.example"), setOf("mdm.customer.example"))
        assertFalse(policy.blocks("mdm.customer.example"))
        assertFalse(policy.blocks("push.mdm.customer.example"))
        assertTrue(policy.blocks("www.customer.example"))
    }

    @Test fun `normalization removes wildcard casing and trailing dot`() {
        assertTrue(WebFilterConfig.normalized(listOf(" *.Example.COM. ")).contains("example.com"))
    }
}
