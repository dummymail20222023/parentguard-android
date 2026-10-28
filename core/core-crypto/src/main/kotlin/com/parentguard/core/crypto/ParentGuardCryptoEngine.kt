package com.parentguard.core.crypto

import com.parentguard.core.protocol.EncryptedRelayEnvelope
import com.parentguard.core.protocol.EnvelopeAssociatedData
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Provides the cryptographic primitives for ParentGuard:
 * 1. Mutual 6-Digit Short Authentication String (SAS) derivation during QR Pairing.
 * 2. On-device phone number pseudonymization via family-salted HMAC-SHA256 (64-bit truncation).
 * 3. End-to-End Authenticated Encryption (HPKE / ECDH-ES + HKDF-SHA256 + AES-256-GCM) with
 *    canonical Associated Data (AAD) and ECDSA/Ed25519 digital signatures.
 */
object ParentGuardCryptoEngine {

    private val canonicalJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Computes a deterministic 6-digit Short Authentication String (SAS) from both parties'
     * public keys and the 128-bit pairing session nonce using HKDF-SHA256.
     */
    fun deriveSixDigitSasCode(
        parentEncryptionPubKey: ByteArray,
        parentSigningPubKey: ByteArray,
        childEncryptionPubKey: ByteArray,
        childSigningPubKey: ByteArray,
        sessionNonce: String
    ): String {
        val ikm = parentEncryptionPubKey + parentSigningPubKey + childEncryptionPubKey + childSigningPubKey
        val prk = hmacSha256(sessionNonce.toByteArray(StandardCharsets.UTF_8), ikm)
        val okm = hmacSha256(prk, "PG-SAS-V1".toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0x01))
        val rawInt = abs(ByteBuffer.wrap(okm, 0, 4).int) % 1_000_000
        return String.format("%06d", rawInt)
    }

    /**
     * Pseudonymizes an E.164 phone number on the child's device into a 16-hex-character
     * (64-bit) token using the family's shared salt so raw phone numbers never leave the device.
     */
    fun pseudonymizePhoneNumber(familySalt: ByteArray, rawPhoneNumber: String): String {
        val normalizedE164 = normalizeToE164(rawPhoneNumber)
        val fullMac = hmacSha256(familySalt, normalizedE164.toByteArray(StandardCharsets.UTF_8))
        return fullMac.take(8).joinToString("") { "%02x".format(it) }
    }

    fun normalizeToE164(rawNumber: String): String {
        val digitsAndPlus = rawNumber.filter { it.isDigit() || it == '+' }
        return if (digitsAndPlus.startsWith("+")) digitsAndPlus else "+$digitsAndPlus"
    }

    /**
     * Seals and signs a [TelemetryPayload] into an [EncryptedRelayEnvelope].
     * Binds [EnvelopeAssociatedData] into the AES-256-GCM authentication tag so headers cannot be tampered with.
     */
    fun sealAndSignEnvelope(
        payload: TelemetryPayload,
        aad: EnvelopeAssociatedData,
        parentPublicEncryptionKey: PublicKey,
        childPrivateSigningKey: PrivateKey
    ): EncryptedRelayEnvelope {
        val cleartextBytes = canonicalJson.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val aadBytes = canonicalJson.encodeToString(aad).toByteArray(StandardCharsets.UTF_8)

        // Generate ephemeral EC keypair for forward-secret E2EE envelope (KEM step)
        val ephemeralKpg = KeyPairGenerator.getInstance("EC")
        ephemeralKpg.initialize(256)
        val ephemeralKeyPair: KeyPair = ephemeralKpg.generateKeyPair()

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(ephemeralKeyPair.private)
        keyAgreement.doPhase(parentPublicEncryptionKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val ephemeralPubEncoded = ephemeralKeyPair.public.encoded
        val aesKeyBytes = hkdfSha256(
            ikm = sharedSecret,
            salt = ephemeralPubEncoded,
            info = "PG-HPKE-AES256GCM".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32
        )
        val ivBytes = hkdfSha256(
            ikm = sharedSecret,
            salt = aadBytes,
            info = "PG-HPKE-IV12".toByteArray(StandardCharsets.UTF_8),
            outputLength = 12
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(aesKeyBytes, "AES"),
            GCMParameterSpec(128, ivBytes)
        )
        cipher.updateAAD(aadBytes)
        val gcmCiphertext = cipher.doFinal(cleartextBytes)

        // Pack [ephemeralPubLength(2 bytes) || ephemeralPubEncoded || iv(12 bytes) || gcmCiphertext]
        val packedBuffer = ByteBuffer.allocate(2 + ephemeralPubEncoded.size + 12 + gcmCiphertext.size)
        packedBuffer.putShort(ephemeralPubEncoded.size.toShort())
        packedBuffer.put(ephemeralPubEncoded)
        packedBuffer.put(ivBytes)
        packedBuffer.put(gcmCiphertext)
        val packedCiphertext = packedBuffer.array()

        // Sign (aadBytes || packedCiphertext) using Child's Hardware Keystore PrivateKey
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(childPrivateSigningKey)
        signer.update(aadBytes)
        signer.update(packedCiphertext)
        val signatureBytes = signer.sign()

        return EncryptedRelayEnvelope(
            aad = aad,
            hpkeCiphertextBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(packedCiphertext),
            childEd25519SignatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes)
        )
    }

    /**
     * Verifies the child device's digital signature and decrypts the [EncryptedRelayEnvelope]
     * on the Parent's Monitor App. Throws [SecurityException] on signature mismatch or AAD tamper.
     */
    fun verifyAndOpenEnvelope(
        envelope: EncryptedRelayEnvelope,
        childPublicSigningKey: PublicKey,
        parentPrivateEncryptionKey: PrivateKey
    ): TelemetryPayload {
        val aadBytes = canonicalJson.encodeToString(envelope.aad).toByteArray(StandardCharsets.UTF_8)
        val packedCiphertext = Base64.getUrlDecoder().decode(envelope.hpkeCiphertextBase64)
        val signatureBytes = Base64.getUrlDecoder().decode(envelope.childEd25519SignatureBase64)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(childPublicSigningKey)
        verifier.update(aadBytes)
        verifier.update(packedCiphertext)
        if (!verifier.verify(signatureBytes)) {
            throw SecurityException("Cryptographic signature verification failed for envelope ${envelope.aad.envelopeId}")
        }

        val buffer = ByteBuffer.wrap(packedCiphertext)
        val ephemeralPubLen = buffer.short.toInt()
        val ephemeralPubEncoded = ByteArray(ephemeralPubLen)
        buffer.get(ephemeralPubEncoded)
        val ivBytes = ByteArray(12)
        buffer.get(ivBytes)
        val gcmCiphertext = ByteArray(buffer.remaining())
        buffer.get(gcmCiphertext)

        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val ephemeralPubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(ephemeralPubEncoded))

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(parentPrivateEncryptionKey)
        keyAgreement.doPhase(ephemeralPubKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val aesKeyBytes = hkdfSha256(
            ikm = sharedSecret,
            salt = ephemeralPubEncoded,
            info = "PG-HPKE-AES256GCM".toByteArray(StandardCharsets.UTF_8),
            outputLength = 32
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKeyBytes, "AES"),
            GCMParameterSpec(128, ivBytes)
        )
        cipher.updateAAD(aadBytes)
        val cleartextBytes = cipher.doFinal(gcmCiphertext)

        return canonicalJson.decodeFromString(String(cleartextBytes, StandardCharsets.UTF_8))
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val prk = hmacSha256(if (salt.isNotEmpty()) salt else ByteArray(32), ikm)
        val result = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < outputLength) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter))
            val copyLen = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }
}
