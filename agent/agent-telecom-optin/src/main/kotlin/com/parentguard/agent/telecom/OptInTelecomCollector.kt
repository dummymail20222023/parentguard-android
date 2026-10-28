package com.parentguard.agent.telecom

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Size
import androidx.core.content.ContextCompat
import com.parentguard.agent.domain.SafetyHelplineFilter
import com.parentguard.core.protocol.CameraPhotoEntry
import com.parentguard.core.protocol.DailyTelecomSummary
import com.parentguard.core.protocol.FullTelecomRecord
import com.parentguard.core.protocol.StorageSummary
import com.parentguard.core.protocol.TelecomEventType
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects Call Log history + matched Call Recording audio files, SMS messages (including a real-time
 * cache that preserves messages even if later deleted from the handset's default SMS app), and
 * the latest 5 camera pictures from storage.
 */
class OptInTelecomCollector(private val context: Context) {

    companion object {
        // Persistent in-memory cache of seen SMS messages keyed by (timestampUtcMs_phoneNumber)
        // so that if an SMS is later deleted from the handset, it remains preserved and marked as deleted!
        private val cachedSmsByKey = ConcurrentHashMap<String, FullTelecomRecord>()
    }

    fun collectFullDailyTelecom(
        windowStartUtcMs: Long,
        windowEndUtcMs: Long
    ): DailyTelecomSummary? {
        val hasCallLog = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        val hasSms = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCallLog && !hasSms) return null

        val zoneId = ZoneId.systemDefault()
        val records = mutableListOf<FullTelecomRecord>()
        val recentCallAudioFiles = queryRecentCallRecordingFiles()

        if (hasCallLog) {
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)

                    var callCount = 0
                    while (cursor.moveToNext() && callCount < 30) {
                        val rawNumber = cursor.getString(numIdx) ?: continue
                        if (SafetyHelplineFilter.isProtectedHelpline(rawNumber)) continue

                        val cachedName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                        val resolvedName = cachedName ?: if (hasContacts) lookupContactName(rawNumber) else null
                        val callType = when (cursor.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> TelecomEventType.CALL_INCOMING
                            CallLog.Calls.OUTGOING_TYPE -> TelecomEventType.CALL_OUTGOING
                            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> TelecomEventType.CALL_MISSED
                            else -> continue
                        }
                        val timestampMs = cursor.getLong(dateIdx)
                        val durationSec = cursor.getLong(durIdx).coerceAtLeast(0L)

                        // Match any call recording audio file created near this call or containing the number/name
                        val matchedAudio = recentCallAudioFiles.firstOrNull { (fileName, dateAddedMs) ->
                            kotlin.math.abs(dateAddedMs - timestampMs) < 300_000L ||
                                (rawNumber.length >= 6 && fileName.contains(rawNumber.takeLast(6)))
                        }?.first

                        records.add(
                            FullTelecomRecord(
                                eventType = callType,
                                timestampUtcMs = timestampMs,
                                phoneNumber = rawNumber,
                                contactName = resolvedName,
                                durationSeconds = durationSec,
                                smsBodyText = null,
                                isSavedInContacts = resolvedName != null,
                                isDeletedFromHandset = false,
                                callRecordingFileName = matchedAudio
                            )
                        )
                        callCount++
                    }
                }
            }
        }

        if (hasSms) {
            val activeHandsetSmsKeys = mutableSetOf<String>()
            val smsProjection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE
            )
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    smsProjection,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC"
                )?.use { cursor ->
                    val addrIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    var smsCount = 0
                    while (cursor.moveToNext() && smsCount < 30) {
                        val rawNumber = cursor.getString(addrIdx) ?: continue
                        if (SafetyHelplineFilter.isProtectedHelpline(rawNumber)) continue

                        val bodyText = cursor.getString(bodyIdx) ?: ""
                        val resolvedName = if (hasContacts) lookupContactName(rawNumber) else null
                        val smsType = when (cursor.getInt(typeIdx)) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> TelecomEventType.SMS_INCOMING
                            Telephony.Sms.MESSAGE_TYPE_SENT -> TelecomEventType.SMS_OUTGOING
                            else -> TelecomEventType.SMS_INCOMING
                        }
                        val timestampMs = cursor.getLong(dateIdx)
                        val key = "${timestampMs}_$rawNumber"
                        activeHandsetSmsKeys.add(key)

                        val rec = FullTelecomRecord(
                            eventType = smsType,
                            timestampUtcMs = timestampMs,
                            phoneNumber = rawNumber,
                            contactName = resolvedName,
                            durationSeconds = 0L,
                            smsBodyText = bodyText,
                            isSavedInContacts = resolvedName != null,
                            isDeletedFromHandset = false
                        )
                        cachedSmsByKey[key] = rec
                        records.add(rec)
                        smsCount++
                    }
                }
            }

            // Include any previously cached SMS that was subsequently deleted from the handset!
            for ((key, cachedRec) in cachedSmsByKey.entries) {
                if (key !in activeHandsetSmsKeys) {
                    records.add(cachedRec.copy(isDeletedFromHandset = true))
                }
            }
        }

        val sortedRecords = records.sortedByDescending { it.timestampUtcMs }
        val localDateIso = Instant.ofEpochMilli(windowEndUtcMs).atZone(zoneId).toLocalDate().toString()
        return DailyTelecomSummary(localDateIso = localDateIso, events = sortedRecords)
    }

    private fun queryRecentCallRecordingFiles(): List<Pair<String, Long>> {
        val list = mutableListOf<Pair<String, Long>>()
        runCatching {
            val proj = arrayOf(
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DATE_ADDED
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val pathIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                var count = 0
                while (c.moveToNext() && count < 20) {
                    val name = c.getString(nameIdx) ?: continue
                    val path = c.getString(pathIdx) ?: ""
                    if (path.contains("Call", ignoreCase = true) ||
                        path.contains("Record", ignoreCase = true) ||
                        name.contains("call", ignoreCase = true)
                    ) {
                        list.add(name to (c.getLong(dateIdx) * 1000L))
                        count++
                    }
                }
            }
        }
        return list
    }

    fun collectLastFiveCameraPictures(limit: Int = 5): StorageSummary? {
        val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasStoragePermission) return null

        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.availableBytes
        val lastFivePhotos = mutableListOf<CameraPhotoEntry>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext() && lastFivePhotos.size < limit) {
                    val mediaId = cursor.getLong(idIdx)
                    val relPath = cursor.getString(pathIdx) ?: "DCIM/Camera/"
                    val photoUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    )
                    val thumbBase64 = loadCompressedThumbnailBase64(photoUri)
                    val takenMs = cursor.getLong(dateTakenIdx).takeIf { it > 0L }
                        ?: (cursor.getLong(dateAddedIdx) * 1000L)

                    lastFivePhotos.add(
                        CameraPhotoEntry(
                            fileName = cursor.getString(nameIdx) ?: "IMG_$mediaId.jpg",
                            relativePath = relPath,
                            mimeType = cursor.getString(mimeIdx) ?: "image/jpeg",
                            sizeBytes = cursor.getLong(sizeIdx),
                            dateTakenUtcMs = takenMs,
                            widthPx = cursor.getInt(widthIdx),
                            heightPx = cursor.getInt(heightIdx),
                            thumbnailJpegBase64 = thumbBase64
                        )
                    )
                }
            }
        }

        return StorageSummary(
            totalStorageBytes = totalBytes,
            freeStorageBytes = freeBytes,
            lastFiveCameraPictures = lastFivePhotos
        )
    }

    private fun loadCompressedThumbnailBase64(photoUri: Uri): String? {
        val thumbBmp = runCatching {
            context.contentResolver.loadThumbnail(photoUri, Size(320, 320), null)
        }.getOrNull() ?: runCatching {
            context.contentResolver.openInputStream(photoUri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()

        if (thumbBmp == null) return null
        return runCatching {
            val baos = ByteArrayOutputStream()
            thumbBmp.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        }.getOrNull()
    }

    private fun lookupContactName(rawNumber: String): String? {
        return runCatching {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber)
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
