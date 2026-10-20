package com.mdmesh.agent.apps

import java.net.URI
import java.net.URLDecoder

/** Parse only canonical Play detail links; never follow a shared redirect or trust an arbitrary host. */
internal object PlayAppLink {
    private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    fun validPackage(value: String) = value.length <= 255 && pkg.matches(value)
    fun fromText(text: String?): String? {
        if (text == null || text.length > 10000) return null
        val found = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).findAll(text).mapNotNull {
            runCatching {
                val uri = URI(it.value.trimEnd('.', ',', ')', ']', ';'))
                if (uri.host?.lowercase() !in setOf("play.google.com", "market.android.com") || uri.userInfo != null || uri.port != -1 || uri.path != "/store/apps/details") return@runCatching null
                val ids = uri.rawQuery.orEmpty().split('&').map { part -> part.split('=', limit = 2) }
                    .filter { part -> URLDecoder.decode(part[0], "UTF-8") == "id" }
                if (ids.size != 1) return@runCatching null
                ids.single().getOrNull(1)?.let { value -> URLDecoder.decode(value, "UTF-8").takeIf(::validPackage) }
            }.getOrNull()
        }.toSet()
        return found.singleOrNull()
    }
}
