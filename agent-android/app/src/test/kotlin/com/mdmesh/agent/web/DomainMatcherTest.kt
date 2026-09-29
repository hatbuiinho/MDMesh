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
}
