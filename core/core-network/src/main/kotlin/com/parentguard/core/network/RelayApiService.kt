package com.parentguard.core.network

import com.parentguard.core.protocol.EncryptedRelayEnvelope
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class PairingSessionInitRequest(
    val sessionNonce: String,
    val parentEncryptionPublicKeyBase64: String,
    val parentSigningPublicKeyBase64: String,
    val expiresInSeconds: Int = 300
)

@Serializable
data class PairingEnrollmentCompleteRequest(
    val sessionNonce: String,
    val childDeviceId: String,
    val childEncryptionPublicKeyBase64: String,
    val childSigningPublicKeyBase64: String,
    val childSignatureBase64: String
)

@Serializable
data class EnvelopeUploadReceipt(
    val envelopeId: String,
    val storedAtUtcMs: Long,
    val hardTtlExpiresAtUtcMs: Long
)

/**
 * Stateless Store-and-Forward Relay API.
 * The relay server holds zero decryption keys and purges envelopes immediately upon Monitor ACK
 * or after a hard 72-hour offline TTL.
 */
interface RelayApiService {

    @POST("v1/pairing/sessions")
    suspend fun createPairingSession(
        @Body request: PairingSessionInitRequest
    ): Response<Unit>

    @POST("v1/pairing/complete")
    suspend fun completePairingSession(
        @Body request: PairingEnrollmentCompleteRequest
    ): Response<Unit>

    @POST("v1/envelopes")
    suspend fun uploadEncryptedEnvelope(
        @Body envelope: EncryptedRelayEnvelope
    ): Response<EnvelopeUploadReceipt>

    @GET("v1/envelopes")
    suspend fun fetchPendingEnvelopes(
        @Query("recipient_device_id") recipientDeviceId: String,
        @Query("since_sequence") sinceSequenceNumber: Long
    ): Response<List<EncryptedRelayEnvelope>>

    @DELETE("v1/envelopes/{envelopeId}")
    suspend fun acknowledgeAndPurgeEnvelope(
        @Path("envelopeId") envelopeId: String
    ): Response<Unit>
}
