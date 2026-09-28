package com.mdmesh.core.sync

/**
 * A device-side condition that must be satisfied before the one-time enrollment token is used.
 *
 * The default keeps the Android-free core reusable. The agent app binds this to its Usage Access
 * check, so background workers cannot bypass the provisioning UI and consume the token early.
 */
fun interface EnrollmentPrerequisite {
    fun isSatisfied(): Boolean

    companion object {
        val ALLOW: EnrollmentPrerequisite = EnrollmentPrerequisite { true }
    }
}
