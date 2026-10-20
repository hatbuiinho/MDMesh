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

    /** Android's resolver cache belongs to a VPN network, not an individual application. */
    fun withoutCache(response: ByteArray): ByteArray {
        if (response.size < 12) return response
        val out = response.copyOf()
        fun word(p: Int): Int { require(p + 1 < out.size); return ((out[p].toInt() and 255) shl 8) or (out[p+1].toInt() and 255) }
        fun name(start: Int): Int {
            var p = start
            while (true) {
                require(p < out.size)
                val n = out[p++].toInt() and 255
                if (n == 0) return p
                if (n and 0xc0 == 0xc0) { require(p < out.size); return p + 1 }
                require(n <= 63 && p + n <= out.size); p += n
            }
        }
        return runCatching {
            var p = 12
            repeat(word(4)) { p = name(p) + 4; require(p <= out.size) }
            repeat(word(6) + word(8) + word(10)) {
                p = name(p); require(p + 10 <= out.size)
                // OPT's TTL field carries EDNS flags; never modify it.
                if (word(p) != 41) for (i in p+4..p+7) out[i] = 0
                val length = word(p+8); p += 10 + length; require(p <= out.size)
            }
            out
        }.getOrElse { error(response, 2) }
    }

    fun error(query: ByteArray, code: Int): ByteArray = query.copyOf().also {
        require(it.size >= 12)
        it[2] = ((it[2].toInt() and 0x01) or 0x80).toByte()
        it[3] = (0x80 or code).toByte()
        for (i in 6..11) it[i] = 0
    }
}
