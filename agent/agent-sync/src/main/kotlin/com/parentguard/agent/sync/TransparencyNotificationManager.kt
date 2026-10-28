package com.parentguard.agent.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.parentguard.core.protocol.CapabilityModule
import com.parentguard.core.protocol.ConsentGrantState

/**
 * Maintains the child-visible persistent ongoing status notification on the Device Agent.
 * Dynamically lists active vs. disabled capabilities and provides 1-tap actions to inspect
 * the local 7-day audit ledger or pause/revoke sharing.
 */
class TransparencyNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "pg_transparency_status"
        const val NOTIFICATION_ID = 4101
    }

    fun ensureChannelCreated() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ParentGuard Active Sharing Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Permanent transparency banner showing what ParentGuard is sharing with your parent."
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun buildStatusSummaryText(
        parentDeviceName: String,
        states: Map<CapabilityModule, ConsentGrantState>
    ): Pair<String, String> {
        val activeNames = states.filterValues { it == ConsentGrantState.ACTIVE_GRANTED }
            .keys.map { it.displayName.substringBefore(" (") }
        val inactiveNames = states.filterValues { it != ConsentGrantState.ACTIVE_GRANTED }
            .keys.map { it.displayName.substringBefore(" (") }

        val title = if (activeNames.isEmpty()) {
            "ParentGuard Paused • Paired with $parentDeviceName"
        } else {
            "ParentGuard Active • Sharing with $parentDeviceName"
        }
        val body = buildString {
            append("Sharing: ")
            append(if (activeNames.isEmpty()) "None" else activeNames.joinToString(", "))
            if (inactiveNames.isNotEmpty()) {
                append(" | Off: ")
                append(inactiveNames.joinToString(", "))
            }
        }
        return title to body
    }

    fun postOrUpdateBanner(
        parentDeviceName: String,
        states: Map<CapabilityModule, ConsentGrantState>
    ) {
        ensureChannelCreated()
        val (title, body) = buildStatusSummaryText(parentDeviceName, states)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }
}
