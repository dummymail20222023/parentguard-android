package com.parentguard.core.crypto

import com.parentguard.core.protocol.CapabilityModule
import com.parentguard.core.protocol.ChargePlugType
import com.parentguard.core.protocol.ConsentGrantState
import com.parentguard.core.protocol.DailyTelecomSummary
import com.parentguard.core.protocol.DeviceStatusSnapshot
import com.parentguard.core.protocol.EnvelopeAssociatedData
import com.parentguard.core.protocol.EnvelopeType
import com.parentguard.core.protocol.NetworkTransportType
import com.parentguard.core.protocol.TelecomEventType
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CryptoAndCanaryTest {

    @Test
    fun `HPKE seal and open round-trip succeeds and rejects tampered AAD sequence number`() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val parentEncKp = kpg.generateKeyPair()
        val childSigKp = kpg.generateKeyPair()

        val familySalt = "pg-family-salt-2026".toByteArray()
        val rawCanaryPhone = "+14155550199"
        val hashedPhone = ParentGuardCryptoEngine.pseudonymizePhoneNumber(familySalt, rawCanaryPhone)

        val payload = TelemetryPayload(
            generatedAtUtcMs = 1761475200000L,
            envelopeType = EnvelopeType.PERIODIC_TELEMETRY_SUMMARY,
            moduleStates = mapOf(
                CapabilityModule.DEVICE_STATUS_BATTERY to ConsentGrantState.ACTIVE_GRANTED,
                CapabilityModule.TELECOM_SMS_CALL_METADATA to ConsentGrantState.ACTIVE_GRANTED
            ),
            deviceStatus = DeviceStatusSnapshot(
                timestampUtcMs = 1761475200000L,
                batteryPercentage = 84,
                isCharging = true,
                chargePlugType = ChargePlugType.USB,
                networkTransport = NetworkTransportType.WIFI,
                isPowerSaveMode = false,
                isBackgroundRestricted = false,
                isStatusBannerVisible = true
            ),
            telecomSummary = DailyTelecomSummary(
                localDateIso = "2026-10-26",
                events = listOf(
                    com.parentguard.core.protocol.FullTelecomRecord(
                        eventType = TelecomEventType.SMS_INCOMING,
                        timestampUtcMs = 1761475200000L,
                        phoneNumber = rawCanaryPhone,
                        contactName = "Alex Tutor",
                        durationSeconds = 0,
                        smsBodyText = "See you at 5 PM for math study group!",
                        isSavedInContacts = true
                    )
                )
            )
        )

        val aad = EnvelopeAssociatedData(
            envelopeId = "env-001",
            childDeviceId = "child-pixel-8a",
            parentDeviceId = "parent-pixel-9",
            monotonicSequenceNumber = 1L,
            createdAtUtcMs = 1761475200000L,
            envelopeType = EnvelopeType.PERIODIC_TELEMETRY_SUMMARY
        )

        val sealed = ParentGuardCryptoEngine.sealAndSignEnvelope(
            payload = payload,
            aad = aad,
            parentPublicEncryptionKey = parentEncKp.public,
            childPrivateSigningKey = childSigKp.private
        )

        val opened = ParentGuardCryptoEngine.verifyAndOpenEnvelope(
            envelope = sealed,
            childPublicSigningKey = childSigKp.public,
            parentPrivateEncryptionKey = parentEncKp.private
        )
        assertEquals(84, opened.deviceStatus?.batteryPercentage)
        assertEquals(rawCanaryPhone, opened.telecomSummary?.events?.first()?.phoneNumber)
        assertEquals("See you at 5 PM for math study group!", opened.telecomSummary?.events?.first()?.smsBodyText)

        // Tamper with sequence number in AAD -> must throw SecurityException
        val tamperedEnvelope = sealed.copy(
            aad = sealed.aad.copy(monotonicSequenceNumber = 99L)
        )
        assertFailsWith<SecurityException> {
            ParentGuardCryptoEngine.verifyAndOpenEnvelope(
                envelope = tamperedEnvelope,
                childPublicSigningKey = childSigKp.public,
                parentPrivateEncryptionKey = parentEncKp.private
            )
        }
    }
}
