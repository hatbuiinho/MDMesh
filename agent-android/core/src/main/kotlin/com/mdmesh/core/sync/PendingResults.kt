package com.mdmesh.core.sync

import com.mdmesh.proto.CommandResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory buffer of command results awaiting delivery. Results are posted on the
 * *next* check-in (the request carries acks for the previous batch).
 *
 * This buffer is deliberately in-memory. The server leases an unacknowledged idempotent
 * `config.apply` back to the delivery queue, so a process death between execution and the next
 * check-in self-heals. Other commands retain their longer server-side execution lease.
 */
@Singleton
class PendingResults @Inject constructor() {

    private val buffer = mutableListOf<CommandResult>()

    @Synchronized
    fun add(results: List<CommandResult>) {
        buffer.addAll(results)
    }

    /** Take and clear the current buffer for delivery. */
    @Synchronized
    fun drain(): List<CommandResult> {
        val snapshot = buffer.toList()
        buffer.clear()
        return snapshot
    }

    /** Put drained results back (front) when delivery failed, so they retry next cycle. */
    @Synchronized
    fun restore(results: List<CommandResult>) {
        buffer.addAll(0, results)
    }
}
