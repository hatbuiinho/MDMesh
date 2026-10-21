package com.mdmesh.agent.ui

/** One display rule shared by Compose and the remaining View screens. */
internal fun requestDeviceLabel(name: String?, number: String?): String {
    fun clean(value: String?) = value?.trim()?.takeUnless { it.isEmpty() || it == "null" }
    return clean(name) ?: clean(number) ?: "Chưa xác định"
}
