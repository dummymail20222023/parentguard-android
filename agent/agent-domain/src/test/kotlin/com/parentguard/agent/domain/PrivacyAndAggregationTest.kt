package com.parentguard.agent.domain

import com.parentguard.core.protocol.CapabilityModule
import com.parentguard.core.protocol.ConsentGrantState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyAndAggregationTest {

    @Test
    fun `crisis helplines are unconditionally filtered out`() {
        assertTrue(SafetyHelplineFilter.isProtectedHelpline("988"))
        assertTrue(SafetyHelplineFilter.isProtectedHelpline("+1-988"))
        assertTrue(SafetyHelplineFilter.isProtectedHelpline("1098"))
        assertTrue(SafetyHelplineFilter.isProtectedHelpline("116111"))
        assertTrue(SafetyHelplineFilter.isProtectedHelpline("741741"))
        assertFalse(SafetyHelplineFilter.isProtectedHelpline("+14155550199"))
    }

    @Test
    fun `usage events aggregator omits apps under 60 seconds and computes accurate totals`() {
        val events = listOf(
            // YouTube: 30 minutes (1,800,000 ms)
            RawUsageTransitionEvent("com.google.android.youtube", "YouTube", "Video", 1_000_000L, true),
            RawUsageTransitionEvent("com.google.android.youtube", "YouTube", "Video", 2_800_000L, false),
            // Calculator: 25 seconds (25,000 ms) -> MUST BE OMITTED (< 60s threshold)
            RawUsageTransitionEvent("com.android.calculator2", "Calculator", "Productivity", 3_000_000L, true),
            RawUsageTransitionEvent("com.android.calculator2", "Calculator", "Productivity", 3_025_000L, false),
            // Self package -> MUST BE OMITTED
            RawUsageTransitionEvent("com.parentguard.agent", "ParentGuard Agent", "Tools", 4_000_000L, true),
            RawUsageTransitionEvent("com.parentguard.agent", "ParentGuard Agent", "Tools", 5_000_000L, false)
        )

        val summary = UsageEventsBucketCalculator.aggregateDailyForegroundTime(
            localDateIso = "2026-10-26",
            timezoneId = "Asia/Kolkata",
            windowStartUtcMs = 0L,
            windowEndUtcMs = 10_000_000L,
            events = events
        )

        assertEquals(1, summary.aggregatedApps.size)
        assertEquals("com.google.android.youtube", summary.aggregatedApps.first().packageName)
        assertEquals(1800L, summary.aggregatedApps.first().totalForegroundSeconds)
    }

    @Test
    fun `consent state machine degrades gracefully on OS permission revocation`() {
        val sm = ConsentStateMachine()
        sm.onChildGrantedModule(CapabilityModule.APP_USAGE_SUMMARIES, androidOsPermissionGranted = true)
        assertTrue(sm.isCollectionAllowed(CapabilityModule.APP_USAGE_SUMMARIES, currentlyHasOsPermission = true))

        // Simulate child revoking PACKAGE_USAGE_STATS in Android Settings
        sm.onAndroidOsPermissionRevoked(CapabilityModule.APP_USAGE_SUMMARIES)
        assertFalse(sm.isCollectionAllowed(CapabilityModule.APP_USAGE_SUMMARIES, currentlyHasOsPermission = false))
        assertEquals(
            ConsentGrantState.DENIED_OR_REVOKED_IN_OS_SETTINGS,
            sm.moduleStates.value[CapabilityModule.APP_USAGE_SUMMARIES]
        )
    }
}
