package com.mdmesh.agent.web
import com.mdmesh.proto.NetworkGrant
import com.mdmesh.proto.NetworkRule
import org.junit.Assert.*
import org.junit.Test

class WebFilterPolicyTest {
    private fun rule(id: String, mode: String = "BLOCKLIST", packages: List<String> = listOf("com.example.chat"), domains: List<String> = listOf("video.example.com")) = NetworkRule(id, id, mode, "SELECTED", packages, domains)
    private fun policy(rules: List<NetworkRule> = listOf(rule("video")), grants: List<NetworkGrant> = emptyList()) = WebFilterPolicy("OFF", emptySet(), setOf("mdm.example.com"), rules=rules, grants=grants)
    @Test fun `same domain is denied only for selected app when owner known`() {
        val p=policy()
        assertTrue(p.blocks("video.example.com", "com.example.chat"))
        assertFalse(p.blocks("video.example.com", "com.example.browser"))
        assertFalse(p.blocks("chat.example.com", "com.example.chat"))
        assertEquals(setOf("com.example.chat"),p.filteredPackages)
    }
    @Test fun `overlapping allowlist cannot override another blocking rule`() {
        val p=policy(listOf(rule("allow", "ALLOWLIST"),rule("deny")))
        assertTrue(p.blocks("video.example.com","com.example.chat"))
        assertTrue(p.blocks("outside.example.com","com.example.chat"))
        assertFalse(p.blocks("mdm.example.com","com.example.chat"))
    }
    @Test fun `grant is exact host exact app and expires at boundary`() {
        val p=policy(grants=listOf(NetworkGrant(1,"video.example.com",listOf("com.example.chat"),2000)))
        assertFalse(p.blocks("video.example.com","com.example.chat",1999))
        assertTrue(p.blocks("video.example.com","com.example.chat",2000))
        assertTrue(p.blocks("sub.video.example.com","com.example.chat",1999))
        assertTrue(p.blocks("video.example.com",null,1999))
    }
    @Test fun `indefinite grant and group do not grant access to unknown owners`() {
        val p=policy(grants=listOf(NetworkGrant(1,"video.example.com",listOf("com.example.chat","com.example.browser"))))
        assertFalse(p.blocks("video.example.com","com.example.chat",Long.MAX_VALUE))
        assertTrue(p.blocks("video.example.com",null,0))
        assertTrue(p.blocks("sub.video.example.com","com.example.chat",0))
    }
    @Test fun `only explicit device-wide grant applies to unknown owner`() {
        assertTrue(policy().blocks("video.example.com",null))
        assertFalse(policy(grants=listOf(NetworkGrant(1,"video.example.com",listOf("*")))).blocks("video.example.com",null))
    }
    @Test fun `legacy selected scope and newly added app groups are unioned`() {
        val p=WebFilterPolicy("BLOCKLIST",setOf("legacy.example.com"),emptySet(),"SELECTED",setOf("com.example.browser"),rules=listOf(rule("group")))
        assertEquals(setOf("com.example.browser","com.example.chat"),p.filteredPackages)
        assertFalse(p.allApps)
        assertFalse(p.blocks("legacy.example.com","com.example.chat"))
        assertTrue(p.blocks("legacy.example.com","com.example.browser"))
    }
}
