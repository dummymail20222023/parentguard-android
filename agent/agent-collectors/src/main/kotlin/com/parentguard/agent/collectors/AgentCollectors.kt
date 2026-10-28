package com.parentguard.agent.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.parentguard.agent.domain.RawUsageTransitionEvent
import com.parentguard.agent.domain.UsageEventsBucketCalculator
import com.parentguard.core.protocol.ChargePlugType
import com.parentguard.core.protocol.DailyAppUsageSummary
import com.parentguard.core.protocol.DeviceStatusSnapshot
import com.parentguard.core.protocol.GeofenceConfig
import com.parentguard.core.protocol.HourlyNotificationSummary
import com.parentguard.core.protocol.LocationPointRecord
import com.parentguard.core.protocol.NetworkTransportType
import com.parentguard.core.protocol.NotificationCountEntry
import com.parentguard.core.protocol.WhatsAppActivityRecord
import com.parentguard.core.protocol.WhatsAppEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DeviceStatusCollector(private val context: Context) {

    fun collectSnapshot(isStatusBannerCurrentlyVisible: Boolean): DeviceStatusSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> ChargePlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> ChargePlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargePlugType.WIRELESS
            else -> ChargePlugType.NONE
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val transport = when {
            capabilities == null -> NetworkTransportType.OFFLINE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
            else -> NetworkTransportType.OFFLINE
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        return DeviceStatusSnapshot(
            timestampUtcMs = System.currentTimeMillis(),
            batteryPercentage = batteryPct.coerceIn(0, 100),
            isCharging = isCharging,
            chargePlugType = plugType,
            networkTransport = transport,
            isPowerSaveMode = pm.isPowerSaveMode,
            isBackgroundRestricted = am.isBackgroundRestricted,
            isStatusBannerVisible = isStatusBannerCurrentlyVisible,
            autoSyncIntervalMinutes = 5
        )
    }
}

/**
 * Capability 7: Real-Time GPS / Network Location & Geofence Collector.
 * Captures current geographical coordinates, reverse-geocodes the street address via Android Geocoder,
 * evaluates distance against the active Geofence radius, and maintains a rolling location history.
 */
class LocationAndGeofenceCollector(private val context: Context) {

    companion object {
        private val locationHistory = CopyOnWriteArrayList<LocationPointRecord>()

        fun getLocationHistory(): List<LocationPointRecord> = locationHistory.toList()
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun recordCurrentLocation(geofence: GeofenceConfig?): List<LocationPointRecord> {
        if (!hasLocationPermission()) return locationHistory.toList()

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var bestLocation: Location? = null
        for (provider in providers) {
            val loc = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (bestLocation == null || loc.time > bestLocation.time) {
                bestLocation = loc
            }
        }

        val loc = bestLocation ?: return locationHistory.toList()
        val addressText = runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
            results?.firstOrNull()?.getAddressLine(0) ?: "${loc.latitude}, ${loc.longitude}"
        }.getOrDefault("${loc.latitude}, ${loc.longitude}")

        var distanceMeters = 0f
        var geofenceState = "INSIDE_SAFE_ZONE"
        if (geofence != null && geofence.enabled && (geofence.centerLatitude != 0.0 || geofence.centerLongitude != 0.0)) {
            val out = FloatArray(1)
            Location.distanceBetween(
                geofence.centerLatitude,
                geofence.centerLongitude,
                loc.latitude,
                loc.longitude,
                out
            )
            distanceMeters = out[0]
            geofenceState = if (distanceMeters <= geofence.radiusMeters) {
                "INSIDE_SAFE_ZONE (${geofence.label})"
            } else {
                "OUTSIDE_GEOFENCE_ALERT (${distanceMeters.toInt()}m from ${geofence.label})"
            }
        }

        val point = LocationPointRecord(
            timestampUtcMs = System.currentTimeMillis(),
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = loc.accuracy,
            streetAddress = addressText,
            geofenceStatus = geofenceState,
            distanceFromHomeMeters = distanceMeters
        )

        locationHistory.add(0, point)
        while (locationHistory.size > 30) {
            locationHistory.removeAt(locationHistory.lastIndex)
        }
        return locationHistory.toList()
    }
}

class AppUsageCollector(private val context: Context) {

    fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun collectDailySummary(windowStartUtcMs: Long, windowEndUtcMs: Long): DailyAppUsageSummary? {
        if (!isUsageAccessGranted()) return null

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = usm.queryEvents(windowStartUtcMs, windowEndUtcMs)
        val pm = context.packageManager
        val transitions = mutableListOf<RawUsageTransitionEvent>()
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val isResume = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            val isPause = event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.ACTIVITY_STOPPED
            if (isResume || isPause) {
                val appInfo = runCatching { pm.getApplicationInfo(event.packageName, 0) }.getOrNull()
                val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: event.packageName
                val category = appInfo?.let {
                    ApplicationInfo.getCategoryTitle(context, it.category)?.toString()
                } ?: "Other"
                transitions.add(
                    RawUsageTransitionEvent(
                        packageName = event.packageName,
                        appLabel = label,
                        categoryName = category,
                        timestampUtcMs = event.timeStamp,
                        isMoveToForeground = isResume
                    )
                )
            }
        }

        val zoneId = ZoneId.systemDefault()
        val localDateIso = Instant.ofEpochMilli(windowEndUtcMs).atZone(zoneId).toLocalDate().toString()
        return UsageEventsBucketCalculator.aggregateDailyForegroundTime(
            localDateIso = localDateIso,
            timezoneId = zoneId.id,
            windowStartUtcMs = windowStartUtcMs,
            windowEndUtcMs = windowEndUtcMs,
            events = transitions
        )
    }
}

/**
 * Capability 3 & 5: Social Media (WhatsApp, Facebook, Messenger, Instagram, Snapchat, LINE, Telegram)
 * & Browser / App Notification Listener.
 */
class OptInNotificationListenerService : NotificationListenerService() {

    companion object {
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SOCIAL_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.orca" to "Messenger",
            "com.snapchat.android" to "Snapchat",
            "jp.naver.line.android" to "LINE",
            "org.telegram.messenger" to "Telegram",
            "com.twitter.android" to "X / Twitter"
        )
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser"
        )

        private val hourlyCounts = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
        private val packageLabels = ConcurrentHashMap<String, Pair<String, String>>()
        private val recentNotificationLog = CopyOnWriteArrayList<WhatsAppActivityRecord>()

        fun getRecentActivityLog(): List<WhatsAppActivityRecord> {
            return recentNotificationLog.toList()
        }

        fun drainWhatsAppActivityLog(): List<WhatsAppActivityRecord> {
            return recentNotificationLog.toList()
        }

        fun drainHourlySummaries(): List<HourlyNotificationSummary> {
            val snapshot = mutableListOf<HourlyNotificationSummary>()
            for ((hourIso, pkgMap) in hourlyCounts.entries) {
                val entries = pkgMap.entries.map { (pkg, count) ->
                    val (label, category) = packageLabels[pkg] ?: (pkg to "Notification")
                    NotificationCountEntry(
                        packageName = pkg,
                        appLabel = label,
                        category = category,
                        receivedCount = count
                    )
                }
                if (entries.isNotEmpty()) {
                    snapshot.add(HourlyNotificationSummary(hourIso, entries))
                }
            }
            return snapshot
        }

        internal fun recordStatusBarNotification(context: Context, sbn: StatusBarNotification) {
            if (sbn.packageName == context.packageName || sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return

            val category = sbn.notification.category ?: Notification.CATEGORY_STATUS
            val pm = context.packageManager
            val appLabel = SOCIAL_PACKAGES[sbn.packageName] ?: runCatching {
                val info = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(sbn.packageName)

            val hourBucketIso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH':00Z'")
                .withZone(ZoneId.of("UTC"))
                .format(Instant.ofEpochMilli(sbn.postTime))

            packageLabels[sbn.packageName] = appLabel to category
            val bucketMap = hourlyCounts.getOrPut(hourBucketIso) { ConcurrentHashMap() }
            bucketMap.merge(sbn.packageName, 1) { old, one -> old + one }

            val extras = sbn.notification.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

            if (title.isEmpty() && text.isEmpty()) return

            val isWhatsApp = sbn.packageName in WHATSAPP_PACKAGES
            val isSocial = sbn.packageName in SOCIAL_PACKAGES.keys
            val isBrowser = sbn.packageName in BROWSER_PACKAGES
            val isCall = category == Notification.CATEGORY_CALL ||
                text.contains("voice call", ignoreCase = true) ||
                text.contains("video call", ignoreCase = true) ||
                title.contains("missed call", ignoreCase = true)

            val eventType = when {
                isWhatsApp && isCall && text.contains("missed", ignoreCase = true) ->
                    WhatsAppEventType.WA_VOICE_CALL_MISSED
                isWhatsApp && isCall && text.contains("video", ignoreCase = true) ->
                    WhatsAppEventType.WA_VIDEO_CALL_INCOMING
                isWhatsApp && isCall ->
                    WhatsAppEventType.WA_VOICE_CALL_INCOMING
                isWhatsApp ->
                    WhatsAppEventType.WA_MESSAGE_INCOMING
                isSocial ->
                    WhatsAppEventType.SOCIAL_CHAT_MESSAGE
                isBrowser ->
                    WhatsAppEventType.BROWSER_SEARCH_NOTIFICATION
                else ->
                    WhatsAppEventType.APP_NOTIFICATION
            }

            val duplicate = recentNotificationLog.any {
                it.senderOrContactTitle == title && it.messageOrCallDetail == text &&
                    kotlin.math.abs(it.timestampUtcMs - sbn.postTime) < 2000L
            }
            if (!duplicate) {
                recentNotificationLog.add(
                    0,
                    WhatsAppActivityRecord(
                        eventType = eventType,
                        timestampUtcMs = sbn.postTime,
                        senderOrContactTitle = title.ifEmpty { appLabel },
                        messageOrCallDetail = text,
                        isGroupConversation = subText.isNotEmpty() || title.contains(":"),
                        appName = appLabel,
                        packageName = sbn.packageName
                    )
                )
                while (recentNotificationLog.size > 60) {
                    recentNotificationLog.removeAt(recentNotificationLog.lastIndex)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            activeNotifications?.forEach { sbn ->
                recordStatusBarNotification(applicationContext, sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        recordStatusBarNotification(applicationContext, sbn)
    }
}
