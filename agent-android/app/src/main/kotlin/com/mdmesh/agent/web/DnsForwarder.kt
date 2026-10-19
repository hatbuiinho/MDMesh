package com.mdmesh.agent.web

import java.net.Inet4Address
import java.net.InetAddress

/** Try the network's other resolvers when one address family or DNS server is unavailable. */
internal object DnsForwarder {
    fun resolve(
        query: ByteArray,
        servers: List<InetAddress>,
        exchange: (InetAddress, ByteArray) -> ByteArray,
    ): ByteArray {
        for (server in servers.distinct().sortedBy { if (it is Inet4Address) 0 else 1 }) {
            val response = runCatching { exchange(server, query) }.getOrNull() ?: continue
            if (response.size < 12 || query.size < 12 || response[0] != query[0] ||
                response[1] != query[1] || response[2].toInt() and 0x80 == 0) continue
            // SERVFAIL/REFUSED from one resolver need not prevent another from answering.
            if (response[3].toInt() and 0x0f in listOf(2, 5)) continue
            return response
        }
        return error(query, 2)
    }

    fun error(query: ByteArray, code: Int): ByteArray = query.copyOf().also {
        require(it.size >= 12)
        it[2] = ((it[2].toInt() and 0x01) or 0x80).toByte()
        it[3] = (0x80 or code).toByte()
        for (i in 6..11) it[i] = 0
    }
}
