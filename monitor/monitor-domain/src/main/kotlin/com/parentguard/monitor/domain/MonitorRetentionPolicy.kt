package com.parentguard.monitor.domain

import com.parentguard.core.protocol.TelemetryPayload

/**
 * Enforces the Parent Monitor App's 30-day rolling retention policy and monotonic sequence verification.
 */
class MonitorRetentionAndSequenceEngine {

    companion object {
        const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
    }

    private var highestVerifiedSequence: Long = 0L
    private val decryptedHistory = mutableListOf<TelemetryPayload>()

    @Synchronized
    fun onVerifiedPayloadDecrypted(sequenceNumber: Long, payload: TelemetryPayload): Boolean {
        if (sequenceNumber <= highestVerifiedSequence) {
            return false // Duplicate or replay envelope ignored idempotently
        }
        highestVerifiedSequence = sequenceNumber
        decryptedHistory.add(0, payload)
        return true
    }

    @Synchronized
    fun purgeRecordsOlderThanThirtyDays(nowUtcMs: Long): Int {
        val cutoffUtcMs = nowUtcMs - THIRTY_DAYS_MS
        val beforeSize = decryptedHistory.size
        decryptedHistory.removeIf { it.generatedAtUtcMs < cutoffUtcMs }
        return beforeSize - decryptedHistory.size
    }

    @Synchronized
    fun getDecryptedHistory(): List<TelemetryPayload> = decryptedHistory.toList()
}
