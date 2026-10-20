package com.mdmesh.agent.apps
import org.junit.Assert.*
import org.junit.Test
class PlayAppLinkTest {
    @Test fun `accepts official share text with title and tracking parameters`() {
        assertEquals("com.zing.zalo",PlayAppLink.fromText("Zalo\nhttps://play.google.com/store/apps/details?id=com.zing.zalo&hl=vi&referrer=share"))
        assertEquals("com.example.chat",PlayAppLink.fromText("Try (https://play.google.com/store/apps/details?id=com.example.chat)."))
        assertEquals("com.example.chat",PlayAppLink.fromText("https://market.android.com/store/apps/details?id=com%2Eexample%2Echat"))
    }
    @Test fun `rejects malicious hosts redirect links duplicate ids and invalid packages`() {
        listOf("https://play.google.com.evil.test/store/apps/details?id=com.example.app",
            "https://play.google.com@evil.test/store/apps/details?id=com.example.app",
            "https://evil.test/?url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.example.app",
            "https://play.google.com/store/apps/details?id=com.example.app&id=com.other.app",
            "https://play.google.com/store/apps/details?id=com.example.app%26x",
            "https://play.google.com/store/apps/details?id=bad",
            "https://play.google.com/store/apps/details?id=com.example.app&%id=broken",
            "https://play.google.com:443/store/apps/details?id=com.example.app").forEach{assertNull(it,PlayAppLink.fromText(it))}
    }
    @Test fun `requires one app and bounds untrusted share payload`() {
        assertNull(PlayAppLink.fromText(null));assertNull(PlayAppLink.fromText("hello"))
        assertNull(PlayAppLink.fromText("x".repeat(10001)))
        assertNull(PlayAppLink.fromText("https://play.google.com/store/apps/details?id=com.one.app https://play.google.com/store/apps/details?id=com.two.app"))
        assertEquals("com.one.app",PlayAppLink.fromText("https://play.google.com/store/apps/details?id=com.one.app https://play.google.com/store/apps/details?id=com.one.app"))
    }
}
