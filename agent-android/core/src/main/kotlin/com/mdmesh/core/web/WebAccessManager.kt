package com.mdmesh.core.web

import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.WebAccessPolicy

fun interface WebAccessManager {
    suspend fun apply(policy: WebAccessPolicy?): String
}

object UnsupportedWebAccessManager : WebAccessManager {
    override suspend fun apply(policy: WebAccessPolicy?): String = ConfigOutcome.UNSUPPORTED
}
