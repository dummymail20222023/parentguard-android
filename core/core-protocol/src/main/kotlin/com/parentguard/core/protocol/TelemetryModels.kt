package com.parentguard.core.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class CapabilityModule(val displayName: String, val isOptIn: Boolean) {
    DEVICE_STATUS_BATTERY("Device Status, Battery & 5m Auto-Sync", isOptIn = false),
    APP_USAGE_SUMMARIES("App Usage & Browser Activity", isOptIn = false),
    NOTIFICATION_METADATA("Social Media (WhatsApp, IG, FB, Snap, LINE)", isOptIn = false),
    TELECOM_SMS_CALL_METADATA("Call Logs, Call Audio Files & SMS (incl. Cached)", isOptIn = false),
    STORAGE_MEDIA_ACCESS("Camera Pictures & Media Files", isOptIn = false),
    PRE_AUTHORIZED_CAMERA_CHECKIN("Remote Front/Back Camera Check-In", isOptIn = false),
    LOCATION_AND_GEOFENCE("Real-Time Location History & Geofence", isOptIn = false)
}

@Serializable
enum class ConsentGrantState {
    ACTIVE_GRANTED,
    PAUSED_BY_CHILD,
    DENIED_OR_REVOKED_IN_OS_SETTINGS,
    DISABLED_BY_PARENT,
    NOT_CONFIGURED
}

@Serializable
enum class EnvelopeType {
    PERIODIC_TELEMETRY_SUMMARY,
    CAPABILITY_STATE_CHANGED,
    OPT_IN_CAPABILITY_REQUEST,
    UNILATERAL_UNPAIR_NOTICE
}

@Serializable
enum class ChargePlugType { AC, USB, WIRELESS, NONE }

@Serializable
enum class NetworkTransportType { WIFI, CELLULAR, OFFLINE }

@Serializable
data class DeviceStatusSnapshot(
    val timestampUtcMs: Long = 0L,
    val batteryPercentage: Int = 0,
    val isCharging: Boolean = false,
    val chargePlugType: ChargePlugType = ChargePlugType.NONE,
    val networkTransport: NetworkTransportType = NetworkTransportType.WIFI,
    val isPowerSaveMode: Boolean = false,
    val isBackgroundRestricted: Boolean = false,
    val isStatusBannerVisible: Boolean = true,
    val autoSyncIntervalMinutes: Int = 5
)

@Serializable
data class AppUsageEntry(
    val packageName: String = "",
    val appLabel: String = "",
    val categoryName: String = "App",
    val totalForegroundSeconds: Long = 60L
)

@Serializable
data class DailyAppUsageSummary(
    val localDateIso: String = "",
    val timezoneId: String = "UTC",
    val aggregatedApps: List<AppUsageEntry> = emptyList()
)

@Serializable
data class NotificationCountEntry(
    val packageName: String = "",
    val appLabel: String = "",
    val category: String = "",
    val receivedCount: Int = 0
)

@Serializable
data class HourlyNotificationSummary(
    val hourBucketUtcIso: String = "",
    val appNotificationCounts: List<NotificationCountEntry> = emptyList()
)

@Serializable
enum class TelecomEventType {
    CALL_INCOMING,
    CALL_OUTGOING,
    CALL_MISSED,
    SMS_INCOMING,
    SMS_OUTGOING
}

@Serializable
data class FullTelecomRecord(
    val eventType: TelecomEventType = TelecomEventType.CALL_INCOMING,
    val timestampUtcMs: Long = 0L,
    val phoneNumber: String = "",
    val contactName: String? = null,
    val durationSeconds: Long = 0L,
    val smsBodyText: String? = null,
    val isSavedInContacts: Boolean = false,
    val isDeletedFromHandset: Boolean = false,
    val callRecordingFileName: String? = null
)

@Serializable
data class DailyTelecomSummary(
    val localDateIso: String = "",
    val events: List<FullTelecomRecord> = emptyList()
)

@Serializable
enum class WhatsAppEventType {
    WA_MESSAGE_INCOMING,
    WA_MESSAGE_OUTGOING,
    WA_VOICE_CALL_INCOMING,
    WA_VOICE_CALL_OUTGOING,
    WA_VOICE_CALL_MISSED,
    WA_VIDEO_CALL_INCOMING,
    SOCIAL_CHAT_MESSAGE,
    BROWSER_SEARCH_NOTIFICATION,
    APP_NOTIFICATION
}

@Serializable
data class WhatsAppActivityRecord(
    val eventType: WhatsAppEventType = WhatsAppEventType.WA_MESSAGE_INCOMING,
    val timestampUtcMs: Long = 0L,
    val senderOrContactTitle: String = "",
    val messageOrCallDetail: String = "",
    val isGroupConversation: Boolean = false,
    val appName: String = "WhatsApp",
    val packageName: String = "com.whatsapp"
)

@Serializable
data class CameraPhotoEntry(
    val fileName: String = "IMG.jpg",
    val relativePath: String = "DCIM/Camera/",
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val dateTakenUtcMs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val thumbnailJpegBase64: String? = null
)

@Serializable
data class StorageSummary(
    val totalStorageBytes: Long = 0L,
    val freeStorageBytes: Long = 0L,
    val lastFiveCameraPictures: List<CameraPhotoEntry> = emptyList()
)

@Serializable
enum class CameraLensFacing { FRONT, BACK }

@Serializable
data class CameraCheckInSnapshot(
    val captureId: String = "",
    val lensFacing: CameraLensFacing = CameraLensFacing.FRONT,
    val capturedAtUtcMs: Long = 0L,
    val resolution: String = "640x480",
    val jpegBase64: String = "",
    val fgsStatusBannerShown: Boolean = true
)

/**
 * Real-time GPS/Network location point and Geofence status.
 */
@Serializable
data class LocationPointRecord(
    val timestampUtcMs: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val streetAddress: String = "",
    val geofenceStatus: String = "INSIDE_SAFE_ZONE",
    val distanceFromHomeMeters: Float = 0f
)

@Serializable
data class GeofenceConfig(
    val label: String = "Home Safe Zone",
    val centerLatitude: Double = 0.0,
    val centerLongitude: Double = 0.0,
    val radiusMeters: Float = 500f,
    val enabled: Boolean = true
)

@Serializable
data class TelemetryPayload(
    val schemaVersion: Int = 2,
    val generatedAtUtcMs: Long = 0L,
    val envelopeType: EnvelopeType = EnvelopeType.PERIODIC_TELEMETRY_SUMMARY,
    val moduleStates: Map<CapabilityModule, ConsentGrantState> = emptyMap(),
    val deviceStatus: DeviceStatusSnapshot? = null,
    val appUsageSummary: DailyAppUsageSummary? = null,
    val notificationSummaries: List<HourlyNotificationSummary> = emptyList(),
    val whatsAppActivityLog: List<WhatsAppActivityRecord> = emptyList(),
    val telecomSummary: DailyTelecomSummary? = null,
    val storageSummary: StorageSummary? = null,
    val cameraCheckInSnapshots: List<CameraCheckInSnapshot> = emptyList(),
    val locationHistory: List<LocationPointRecord> = emptyList(),
    val geofenceConfig: GeofenceConfig? = null,
    val revocationReasonNote: String? = null
)

@Serializable
data class EnvelopeAssociatedData(
    val envelopeId: String = "",
    val childDeviceId: String = "",
    val parentDeviceId: String = "",
    val monotonicSequenceNumber: Long = 1L,
    val createdAtUtcMs: Long = 0L,
    val envelopeType: EnvelopeType = EnvelopeType.PERIODIC_TELEMETRY_SUMMARY,
    val schemaVersion: Int = 2
)

@Serializable
data class EncryptedRelayEnvelope(
    val aad: EnvelopeAssociatedData = EnvelopeAssociatedData(),
    val hpkeCiphertextBase64: String = "",
    val childEd25519SignatureBase64: String = ""
)

@Serializable
data class RemoteParentCommand(
    val commandId: String = "",
    val action: String = "SYNC_ALL",
    val requestedAtUtcMs: Long = 0L,
    val geofenceLat: Double? = null,
    val geofenceLng: Double? = null,
    val geofenceRadiusMeters: Float? = null
)
