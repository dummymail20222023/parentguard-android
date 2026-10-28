package com.parentguard.agent.domain

import com.parentguard.core.protocol.AppUsageEntry
import com.parentguard.core.protocol.DailyAppUsageSummary

/**
 * Represents a raw Android UsageEvents event processed strictly in local volatile memory.
 */
data class RawUsageTransitionEvent(
    val packageName: String,
    val appLabel: String,
    val categoryName: String,
    val timestampUtcMs: Long,
    val isMoveToForeground: Boolean // true = ACTIVITY_RESUMED, false = ACTIVITY_PAUSED / STOPPED
)

/**
 * Processes raw Android UsageEvents in local memory to compute accurate local-midnight daily
 * foreground durations while discarding granular launch/close timestamps and omitting apps
 * used for less than 60 seconds.
 */
object UsageEventsBucketCalculator {

    private const val MIN_REPORTABLE_DURATION_SECONDS = 60L

    fun aggregateDailyForegroundTime(
        localDateIso: String,
        timezoneId: String,
        windowStartUtcMs: Long,
        windowEndUtcMs: Long,
        events: List<RawUsageTransitionEvent>,
        excludedSelfPackages: Set<String> = setOf("com.parentguard.agent", "com.android.systemui")
    ): DailyAppUsageSummary {
        val sortedEvents = events
            .filter { it.timestampUtcMs in windowStartUtcMs..windowEndUtcMs && it.packageName !in excludedSelfPackages }
            .sortedBy { it.timestampUtcMs }

        val activeStartByPackage = mutableMapOf<String, Long>()
        val accumulatedMsByPackage = mutableMapOf<String, Long>()
        val metadataByPackage = mutableMapOf<String, Pair<String, String>>()

        for (event in sortedEvents) {
            metadataByPackage[event.packageName] = event.appLabel to event.categoryName
            if (event.isMoveToForeground) {
                activeStartByPackage.putIfAbsent(event.packageName, event.timestampUtcMs)
            } else {
                val startMs = activeStartByPackage.remove(event.packageName)
                if (startMs != null && event.timestampUtcMs > startMs) {
                    val deltaMs = event.timestampUtcMs - startMs
                    accumulatedMsByPackage[event.packageName] =
                        (accumulatedMsByPackage[event.packageName] ?: 0L) + deltaMs
                }
            }
        }

        // Close any still-foreground sessions at windowEndUtcMs
        for ((pkg, startMs) in activeStartByPackage) {
            if (windowEndUtcMs > startMs) {
                accumulatedMsByPackage[pkg] =
                    (accumulatedMsByPackage[pkg] ?: 0L) + (windowEndUtcMs - startMs)
            }
        }

        val reportableEntries = accumulatedMsByPackage.entries
            .mapNotNull { (pkg, totalMs) ->
                val totalSeconds = totalMs / 1000L
                if (totalSeconds < MIN_REPORTABLE_DURATION_SECONDS) {
                    null
                } else {
                    val (label, category) = metadataByPackage[pkg] ?: (pkg to "Uncategorized")
                    AppUsageEntry(
                        packageName = pkg,
                        appLabel = label,
                        categoryName = category,
                        totalForegroundSeconds = totalSeconds
                    )
                }
            }
            .sortedByDescending { it.totalForegroundSeconds }

        return DailyAppUsageSummary(
            localDateIso = localDateIso,
            timezoneId = timezoneId,
            aggregatedApps = reportableEntries
        )
    }
}
