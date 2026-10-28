package com.parentguard.agent.data

import com.parentguard.core.protocol.EncryptedRelayEnvelope
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

data class ChildTransparencyAuditRecord(
    val envelopeId: String,
    val sentAtUtcMs: Long,
    val summaryHeadline: String,
    val cleartextPayloadJson: String,
    val payloadSnapshot: TelemetryPayload
)

/**
 * Enforces the core ParentGuard Transparency Invariant:
 * An encrypted envelope CANNOT be queued into the network outbox unless its exact human-readable
 * cleartext summary is simultaneously written to the child's 7-day Transparency Audit Ledger.
 */
class TransactionalAuditAndOutboxRepository {

    companion object {
        const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
        const val SEVENTY_TWO_HOURS_MS = 72L * 60L * 60L * 1000L
    }

    private val auditLedger = CopyOnWriteArrayList<ChildTransparencyAuditRecord>()
    private val pendingEncryptedOutbox = CopyOnWriteArrayList<EncryptedRelayEnvelope>()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Atomically records the cleartext payload into the Child's Transparency Ledger
     * and enqueues the encrypted envelope for upload.
     */
    @Synchronized
    fun recordInChildLedgerAndEnqueueOutbox(
        cleartextPayload: TelemetryPayload,
        encryptedEnvelope: EncryptedRelayEnvelope,
        humanReadableHeadline: String
    ) {
        require(cleartextPayload.generatedAtUtcMs == encryptedEnvelope.aad.createdAtUtcMs) {
            "Ledger parity check failed: timestamp mismatch between cleartext audit record and encrypted envelope."
        }
        val auditRecord = ChildTransparencyAuditRecord(
            envelopeId = encryptedEnvelope.aad.envelopeId,
            sentAtUtcMs = cleartextPayload.generatedAtUtcMs,
            summaryHeadline = humanReadableHeadline,
            cleartextPayloadJson = json.encodeToString(cleartextPayload),
            payloadSnapshot = cleartextPayload
        )
        auditLedger.add(0, auditRecord)
        pendingEncryptedOutbox.add(encryptedEnvelope)
    }

    fun getChildTransparencyAuditHistory(): List<ChildTransparencyAuditRecord> = auditLedger.toList()

    fun getPendingOutboxEnvelopes(): List<EncryptedRelayEnvelope> = pendingEncryptedOutbox.toList()

    /**
     * Immediately purges an envelope from the child's outbox upon HTTP 200 OK upload receipt.
     */
    fun onEnvelopeUploadedSuccess(envelopeId: String) {
        pendingEncryptedOutbox.removeIf { it.aad.envelopeId == envelopeId }
    }

    /**
     * Enforces the strict 7-day rolling retention window on the Child's Transparency Audit Ledger
     * and the 72-hour offline cap on the Outbox.
     */
    fun purgeExpiredRecords(nowUtcMs: Long) {
        val sevenDaysAgo = nowUtcMs - SEVEN_DAYS_MS
        val seventyTwoHoursAgo = nowUtcMs - SEVENTY_TWO_HOURS_MS
        auditLedger.removeIf { it.sentAtUtcMs < sevenDaysAgo }
        pendingEncryptedOutbox.removeIf { it.aad.createdAtUtcMs < seventyTwoHoursAgo }
    }

    /**
     * Invoked when the child revokes a capability or unpairs the device.
     */
    fun wipeOutboxImmediately() {
        pendingEncryptedOutbox.clear()
    }
}
