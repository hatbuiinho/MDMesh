package com.mdmesh.agent.web

import java.net.InetAddress
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class DnsForwarderTest {
    private val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        6, 103, 111, 111, 103, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)
    private val ipv4 = InetAddress.getByName("203.113.131.6")
    private val second = InetAddress.getByName("203.113.131.5")
    private val ipv6 = InetAddress.getByName("2402:800:20ff:7777::1")
    private val answer get() = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }

    @Test fun `IPv6 first carrier list prefers working IPv4`() {
        val attempted = mutableListOf<InetAddress>()
        assertArrayEquals(answer, DnsForwarder.resolve(query, listOf(ipv6, ipv4)) { server, _ ->
            attempted += server
            if (server == ipv6) error("Network unreachable")
            answer
        })
        assertEquals(listOf(ipv4), attempted)
    }

    @Test fun `timeout tries remaining server including IPv6 only networks`() {
        val attempted = mutableListOf<InetAddress>()
        assertArrayEquals(answer, DnsForwarder.resolve(query, listOf(ipv4, ipv6)) { server, _ ->
            attempted += server
            if (server == ipv4) throw SocketTimeoutException()
            answer
        })
        assertEquals(listOf(ipv4, ipv6), attempted)
        assertArrayEquals(answer, DnsForwarder.resolve(query, listOf(ipv6)) { _, _ -> answer })
    }

    @Test fun `total failure returns SERVFAIL with original question and transaction`() {
        val result = DnsForwarder.resolve(query, listOf(ipv4, second)) { _, _ -> error("unreachable") }
        assertEquals(2, result[3].toInt() and 15)
        assertArrayEquals(query.copyOfRange(12, query.size), result.copyOfRange(12, result.size))
        assertEquals(query[0], result[0])
        assertEquals(query[1], result[1])
        assertArrayEquals(result, DnsForwarder.resolve(query, emptyList()) { _, _ -> error("no server") })
    }

    @Test fun `invalid transaction and server failure try next resolver`() {
        for (bad in listOf(byteArrayOf(0), answer.also { it[0] = 0 }, DnsForwarder.error(query, 2))) {
            assertArrayEquals(answer, DnsForwarder.resolve(query, listOf(ipv4, second)) { server, _ ->
                if (server == ipv4) bad else answer
            })
        }
    }

    @Test fun `NXDOMAIN is preserved rather than bypassed`() {
        val denied = DnsForwarder.error(query, 3)
        var attempts = 0
        assertArrayEquals(denied, DnsForwarder.resolve(query, listOf(ipv4, second)) { _, _ ->
            attempts++
            denied
        })
        assertEquals(1, attempts)
    }
}
