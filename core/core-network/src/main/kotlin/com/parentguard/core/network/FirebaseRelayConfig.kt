package com.parentguard.core.network

import com.parentguard.core.protocol.RemoteParentCommand
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Pre-configured with your Firebase Realtime Database URL (asia-southeast1).
 * You can also edit this URL at any time inside the app's text field.
 */
object FirebaseRelayConfig {
    const val DEFAULT_FIREBASE_DB_URL: String = "https://parentguard-1f165-default-rtdb.asia-southeast1.firebasedatabase.app"
    const val DEFAULT_FIREBASE_AUTH_SECRET: String = ""
    const val DEFAULT_FAMILY_ID: String = "family_001"
}

@OptIn(ExperimentalSerializationApi::class)
object FirebaseRealtimeRelayClient {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildNodeUrl(baseUrl: String, familyId: String, nodePath: String, authSecret: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanFamily = familyId.trim().ifEmpty { "family_001" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val baseEndpoint = "$cleanBase/families/$cleanFamily/$nodePath.json"
        return if (authSecret.isNotBlank()) {
            "$baseEndpoint?auth=${authSecret.trim()}"
        } else {
            baseEndpoint
        }
    }

    fun isConfigured(baseUrl: String): Boolean {
        val clean = baseUrl.trim()
        return clean.startsWith("https://") &&
            (clean.contains("firebaseio.com") || clean.contains("firebasedatabase.app"))
    }

    fun uploadTelemetryPayload(
        baseUrl: String,
        authSecret: String,
        familyId: String,
        payload: TelemetryPayload
    ): Result<Unit> = runCatching {
        val url = buildNodeUrl(baseUrl, familyId, "latest_telemetry", authSecret)
        val bodyStr = json.encodeToString(payload)
        val req = Request.Builder()
            .url(url)
            .put(bodyStr.toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Firebase HTTP ${resp.code}: ${resp.message}")
            }
        }
    }

    fun fetchLatestTelemetryPayload(
        baseUrl: String,
        authSecret: String,
        familyId: String
    ): Result<TelemetryPayload?> = runCatching {
        val url = buildNodeUrl(baseUrl, familyId, "latest_telemetry", authSecret)
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Firebase HTTP ${resp.code}: ${resp.message}")
            }
            val raw = resp.body?.string()?.trim()
            if (raw.isNullOrEmpty() || raw == "null") {
                null
            } else {
                json.decodeFromString<TelemetryPayload>(raw)
            }
        }
    }

    fun sendRemoteCommand(
        baseUrl: String,
        authSecret: String,
        familyId: String,
        command: RemoteParentCommand
    ): Result<Unit> = runCatching {
        val url = buildNodeUrl(baseUrl, familyId, "pending_command", authSecret)
        val bodyStr = json.encodeToString(command)
        val req = Request.Builder()
            .url(url)
            .put(bodyStr.toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Firebase HTTP ${resp.code}: ${resp.message}")
            }
        }
    }

    fun fetchPendingCommand(
        baseUrl: String,
        authSecret: String,
        familyId: String
    ): Result<RemoteParentCommand?> = runCatching {
        val url = buildNodeUrl(baseUrl, familyId, "pending_command", authSecret)
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val raw = resp.body?.string()?.trim()
            if (raw.isNullOrEmpty() || raw == "null") {
                null
            } else {
                json.decodeFromString<RemoteParentCommand>(raw)
            }
        }
    }

    fun clearPendingCommand(
        baseUrl: String,
        authSecret: String,
        familyId: String
    ): Result<Unit> = runCatching {
        val url = buildNodeUrl(baseUrl, familyId, "pending_command", authSecret)
        val req = Request.Builder().url(url).delete().build()
        httpClient.newCall(req).execute().close()
    }
}
