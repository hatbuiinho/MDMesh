package com.mdmesh.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Check-in status for the current agent process. [CheckInCoordinator]
 * records it on every failed cycle and clears it on the next successful one, so an operator
 * can see why enrollment isn't progressing. A success timestamp records an actual check-in,
 * rather than inferring that a device is connected from the absence of an error.
 */
@Singleton
class SyncStatus @Inject constructor() {

    data class Failure(val message: String, val atMillis: Long)

    private val _lastError = MutableStateFlow<Failure?>(null)
    val lastError: StateFlow<Failure?> = _lastError

    private val _lastSuccessAt = MutableStateFlow<Long?>(null)
    val lastSuccessAt: StateFlow<Long?> = _lastSuccessAt

    fun recordFailure(t: Throwable) {
        _lastError.value = Failure(t.message ?: t.javaClass.simpleName, System.currentTimeMillis())
    }

    fun clear() {
        _lastSuccessAt.value = System.currentTimeMillis()
        _lastError.value = null
    }
}
