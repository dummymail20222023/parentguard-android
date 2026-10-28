package com.parentguard.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.parentguard.agent.collectors.AppUsageCollector
import com.parentguard.agent.collectors.DeviceStatusCollector
import com.parentguard.agent.collectors.LocationAndGeofenceCollector
import com.parentguard.agent.collectors.OptInNotificationListenerService
import com.parentguard.agent.collectors.PreAuthorizedCameraService
import com.parentguard.agent.domain.ConsentStateMachine
import com.parentguard.agent.telecom.OptInTelecomCollector
import com.parentguard.core.network.FirebaseRealtimeRelayClient
import com.parentguard.core.network.FirebaseRelayConfig
import com.parentguard.core.protocol.CameraCheckInSnapshot
import com.parentguard.core.protocol.CameraLensFacing
import com.parentguard.core.protocol.CapabilityModule
import com.parentguard.core.protocol.ConsentGrantState
import com.parentguard.core.protocol.EnvelopeType
import com.parentguard.core.protocol.GeofenceConfig
import com.parentguard.core.protocol.TelemetryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class AgentMainActivity : ComponentActivity() {
    private val consentStateMachine = ConsentStateMachine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AgentTransparencyDashboardScreen(
                    stateMachine = consentStateMachine,
                    onUnpairClicked = { }
                )
            }
        }
    }
}

@Composable
fun AgentTransparencyDashboardScreen(
    stateMachine: ConsentStateMachine,
    onUnpairClicked: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pg_agent_prefs", Context.MODE_PRIVATE) }
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

    val moduleStates by stateMachine.moduleStates.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var syncStatusText by remember { mutableStateOf("5-Min Auto-Sync Active • Ready.") }

    val recentCameraSnapshots = remember { mutableStateListOf<CameraCheckInSnapshot>() }
    var activeGeofence by remember {
        mutableStateOf(
            GeofenceConfig(
                label = "Home Safe Zone",
                centerLatitude = 0.0,
                centerLongitude = 0.0,
                radiusMeters = 500f,
                enabled = true
            )
        )
    }
    var latestCollectedPayload by remember { mutableStateOf<TelemetryPayload?>(null) }
    var lastHandledCommandId by remember { mutableStateOf("") }
    var lastAutoSyncEpochMs by remember { mutableLongStateOf(0L) }

    suspend fun collectAndUploadToFirebase(
        requestedCameraLens: CameraLensFacing? = null,
        reasonNote: String = "5-Min Auto-Sync"
    ) {
        withContext(Dispatchers.IO) {
            val nowMs = System.currentTimeMillis()
            val startOfDayMs = nowMs - (24L * 60L * 60L * 1000L)

            if (requestedCameraLens != null) {
                val snap = PreAuthorizedCameraService.captureOffscreenJpeg(context, requestedCameraLens)
                if (snap != null) {
                    recentCameraSnapshots.add(0, snap)
                    while (recentCameraSnapshots.size > 6) {
                        recentCameraSnapshots.removeAt(recentCameraSnapshots.lastIndex)
                    }
                }
            }

            val deviceStatus = runCatching {
                DeviceStatusCollector(context).collectSnapshot(true)
            }.getOrNull()

            val usageSummary = runCatching {
                AppUsageCollector(context).collectDailySummary(startOfDayMs, nowMs)
            }.getOrNull()

            val telecomCollector = OptInTelecomCollector(context)
            val telecomSummary = runCatching {
                telecomCollector.collectFullDailyTelecom(startOfDayMs, nowMs)
            }.getOrNull()

            val storageSummary = runCatching {
                telecomCollector.collectLastFiveCameraPictures(5)
            }.getOrNull()

            val locationPoints = runCatching {
                val locs = LocationAndGeofenceCollector(context).recordCurrentLocation(activeGeofence)
                // If Geofence center hasn't been initialized yet, anchor it to first valid GPS fix
                if (activeGeofence.centerLatitude == 0.0 && locs.isNotEmpty()) {
                    activeGeofence = activeGeofence.copy(
                        centerLatitude = locs.first().latitude,
                        centerLongitude = locs.first().longitude
                    )
                }
                locs
            }.getOrDefault(emptyList())

            val payload = TelemetryPayload(
                generatedAtUtcMs = nowMs,
                envelopeType = EnvelopeType.PERIODIC_TELEMETRY_SUMMARY,
                moduleStates = stateMachine.moduleStates.value,
                deviceStatus = deviceStatus,
                appUsageSummary = usageSummary,
                notificationSummaries = OptInNotificationListenerService.drainHourlySummaries(),
                whatsAppActivityLog = OptInNotificationListenerService.getRecentActivityLog(),
                telecomSummary = telecomSummary,
                storageSummary = storageSummary,
                cameraCheckInSnapshots = recentCameraSnapshots.toList(),
                locationHistory = locationPoints,
                geofenceConfig = activeGeofence,
                revocationReasonNote = reasonNote
            )

            latestCollectedPayload = payload
            lastAutoSyncEpochMs = nowMs

            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
            if (FirebaseRealtimeRelayClient.isConfigured(firebaseDbUrl)) {
                val res = FirebaseRealtimeRelayClient.uploadTelemetryPayload(
                    baseUrl = firebaseDbUrl,
                    authSecret = authSecret,
                    familyId = familyId,
                    payload = payload
                )
                syncStatusText = if (res.isSuccess) {
                    "Synced ($timeLabel) • $reasonNote (Next auto-sync in 5m)"
                } else {
                    "Firebase Error: ${res.exceptionOrNull()?.message}"
                }
            } else {
                syncStatusText = "Collected locally ($timeLabel)."
            }
        }
    }

    // Automatic 5-Minute Background Sync + 4-Second Remote Command Listener
    LaunchedEffect(firebaseDbUrl, familyId) {
        collectAndUploadToFirebase(null, "Initial Launch Auto-Sync")
        while (isActive) {
            val now = System.currentTimeMillis()
            // 1. Trigger automatic 5-minute sync cycle (300,000 ms)
            if (now - lastAutoSyncEpochMs >= 5L * 60L * 1000L) {
                collectAndUploadToFirebase(null, "Scheduled 5-Minute Auto-Sync")
            }

            // 2. Poll Firebase for instant Admin commands (Front/Back Camera, Sync, Geofence)
            if (FirebaseRealtimeRelayClient.isConfigured(firebaseDbUrl)) {
                val cmdResult = withContext(Dispatchers.IO) {
                    FirebaseRealtimeRelayClient.fetchPendingCommand(firebaseDbUrl, authSecret, familyId)
                }
                val cmd = cmdResult.getOrNull()
                if (cmd != null && cmd.commandId.isNotEmpty() && cmd.commandId != lastHandledCommandId) {
                    lastHandledCommandId = cmd.commandId
                    withContext(Dispatchers.IO) {
                        FirebaseRealtimeRelayClient.clearPendingCommand(firebaseDbUrl, authSecret, familyId)
                    }
                    when (cmd.action) {
                        "CAPTURE_FRONT" -> collectAndUploadToFirebase(CameraLensFacing.FRONT, "Admin triggered FRONT camera")
                        "CAPTURE_BACK" -> collectAndUploadToFirebase(CameraLensFacing.BACK, "Admin triggered BACK camera")
                        "SET_GEOFENCE" -> {
                            val gLat = cmd.geofenceLat
                            val gLng = cmd.geofenceLng
                            val gRad = cmd.geofenceRadiusMeters
                            if (gLat != null && gLng != null) {
                                activeGeofence = activeGeofence.copy(
                                    centerLatitude = gLat,
                                    centerLongitude = gLng,
                                    radiusMeters = gRad ?: 500f
                                )
                            }
                            collectAndUploadToFirebase(null, "Admin updated Geofence")
                        }
                        else -> collectAndUploadToFirebase(null, "Admin requested full sync")
                    }
                }
            }
            delay(4000L)
        }
    }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { resultMap ->
        val grantedCount = resultMap.values.count { it }
        syncStatusText = "Granted $grantedCount/${resultMap.size} permissions. Syncing..."
        CapabilityModule.entries.forEach { mod ->
            stateMachine.onChildGrantedModule(mod, true)
        }
        scope.launch {
            collectAndUploadToFirebase(null, "All Permissions Granted Sync")
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "ParentGuard • Agent (5m Auto-Sync)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = syncStatusText,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // One-Time Permission Setup Buttons
            item {
                Button(
                    onClick = {
                        val perms = mutableListOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.READ_CONTACTS
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                        } else {
                            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                        runtimePermissionsLauncher.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("1. Grant All Permissions (Location, Camera, SMS, Calls, Media)")
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2. Social & Notifs")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("3. Usage & Browser")
                    }
                }
            }

            // Quick Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                syncStatusText = "Capturing FRONT camera..."
                                collectAndUploadToFirebase(CameraLensFacing.FRONT, "Captured Front Camera")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Snap Front")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                syncStatusText = "Capturing BACK camera..."
                                collectAndUploadToFirebase(CameraLensFacing.BACK, "Captured Back Camera")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Snap Back")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                syncStatusText = "Syncing all data to Firebase..."
                                collectAndUploadToFirebase(null, "Manual Full Sync")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sync Now")
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Live Data & Media") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Firebase & Modules") }
                    )
                }
            }

            if (selectedTab == 0) {
                val p = latestCollectedPayload

                // Location & Geofence Card
                item {
                    val locs = p?.locationHistory.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Real-Time Location & Geofence (${locs.size} pts)",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (locs.isEmpty()) {
                                Text(
                                    text = "Grant Location permission above to record GPS & street address.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                val latestLoc = locs.first()
                                Text(
                                    text = "📍 ${latestLoc.streetAddress}\nCoords: ${latestLoc.latitude}, ${latestLoc.longitude} (±${latestLoc.accuracyMeters.toInt()}m)\nGeofence: ${latestLoc.geofenceStatus}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                // Captured Front / Back Camera Snapshots
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Captured Front / Back Camera Photos (${recentCameraSnapshots.size})",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (recentCameraSnapshots.isEmpty()) {
                                Text(
                                    text = "Tap 'Snap Front' or 'Snap Back' above (or trigger remotely from Admin app).",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                recentCameraSnapshots.forEach { snap ->
                                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snap.capturedAtUtcMs))
                                    val bmp = remember(snap.captureId) {
                                        runCatching {
                                            val bytes = Base64.getDecoder().decode(snap.jpegBase64)
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                        }.getOrNull()
                                    }
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        Text(
                                            text = "${snap.lensFacing.name} Camera • $timeStr (${snap.resolution})",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (bmp != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = snap.lensFacing.name,
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

                // Last 5 Camera Pictures from Storage
                item {
                    val photos = p?.storageSummary?.lastFiveCameraPictures.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Last 5 Camera Pictures from Storage (${photos.size})",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            photos.forEachIndexed { idx, photo ->
                                val bmp = remember(photo.fileName, photo.dateTakenUtcMs) {
                                    runCatching {
                                        val b64 = photo.thumbnailJpegBase64 ?: return@runCatching null
                                        val bytes = Base64.getDecoder().decode(b64)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                    }.getOrNull()
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = photo.fileName,
                                            modifier = Modifier.size(88.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "#${idx + 1} ${photo.fileName}",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "${photo.relativePath} • ${photo.sizeBytes / 1024} KB",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Social Media & Notifications
                item {
                    val waLogs = p?.whatsAppActivityLog.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Social Media & Notifications (${waLogs.size})",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            waLogs.take(20).forEach { w ->
                                val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(w.timestampUtcMs))
                                Text(
                                    text = "• [$t] [${w.appName}] ${w.senderOrContactTitle}: \"${w.messageOrCallDetail}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // SMS & Call History
                item {
                    val events = p?.telecomSummary?.events.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "SMS (incl. Deleted) & Call Logs (${events.size})",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            events.take(25).forEach { e ->
                                val t = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.timestampUtcMs))
                                val namePart = e.contactName?.let { " ($it)" } ?: ""
                                val delTag = if (e.isDeletedFromHandset) " [DELETED ON HANDSET]" else ""
                                val recTag = e.callRecordingFileName?.let { "\n   🎙️ Call Audio: $it" } ?: ""
                                val detailPart = if (!e.smsBodyText.isNullOrEmpty()) {
                                    "\n   💬 SMS$delTag: \"${e.smsBodyText}\""
                                } else {
                                    " • Duration: ${e.durationSeconds}s$recTag"
                                }
                                Text(
                                    text = "• [$t] ${e.eventType.name}: ${e.phoneNumber}$namePart$detailPart",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Firebase Realtime Database Relay",
                                fontWeight = FontWeight.Bold
                            )
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

                items(CapabilityModule.entries) { module ->
                    val state = moduleStates[module] ?: ConsentGrantState.NOT_CONFIGURED
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = module.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Status: ${state.name}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = state == ConsentGrantState.ACTIVE_GRANTED,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        stateMachine.onChildGrantedModule(module, true)
                                    } else {
                                        stateMachine.onChildPausedModuleInApp(module)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
