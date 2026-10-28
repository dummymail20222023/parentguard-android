package com.parentguard.agent.collectors

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.parentguard.core.protocol.CameraCheckInSnapshot
import com.parentguard.core.protocol.CameraLensFacing
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Capability 6: One-Time Pre-Authorized Front/Back Camera Service.
 * Uses a dummy SurfaceTexture preview + Camera2 ImageReader with sensor auto-exposure warmup
 * so Front and Back camera captures succeed reliably across Vivo, Xiaomi, Samsung, Realme, and Pixel.
 */
class PreAuthorizedCameraService : Service() {

    companion object {
        const val EXTRA_LENS_FACING = "extra_lens_facing"

        fun hasCameraPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun resolveCameraIdForLens(context: Context, lens: CameraLensFacing): Pair<String, Int>? {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (lens == CameraLensFacing.FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            for (cameraId in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(cameraId)
                if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                    val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    return cameraId to sensorOrientation
                }
            }
            val fallbackId = cm.cameraIdList.firstOrNull() ?: return null
            val orient = cm.getCameraCharacteristics(fallbackId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            return fallbackId to orient
        }

        @SuppressLint("MissingPermission")
        fun captureOffscreenJpeg(context: Context, lens: CameraLensFacing): CameraCheckInSnapshot? {
            if (!hasCameraPermission(context)) return null
            val (cameraId, sensorOrientation) = resolveCameraIdForLens(context, lens) ?: return null
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val handlerThread = HandlerThread("PGCameraCaptureThread").apply { start() }
            val handler = Handler(handlerThread.looper)
            val latch = CountDownLatch(1)
            var capturedBase64: String? = null

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            val dummyTexture = SurfaceTexture(10).apply { setDefaultBufferSize(640, 480) }
            val dummyPreviewSurface = Surface(dummyTexture)
            var openedCamera: CameraDevice? = null

            imageReader.setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    try {
                        val buffer = image.planes[0].buffer
                        val rawBytes = ByteArray(buffer.remaining())
                        buffer.get(rawBytes)
                        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        val finalBytes = if (decoded != null) {
                            val rotated = if (sensorOrientation != 0) {
                                val matrix = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
                                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                            } else {
                                decoded
                            }
                            val baos = ByteArrayOutputStream()
                            rotated.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                            baos.toByteArray()
                        } else {
                            rawBytes
                        }
                        capturedBase64 = Base64.getEncoder().encodeToString(finalBytes)
                    } finally {
                        image.close()
                        latch.countDown()
                    }
                }
            }, handler)

            try {
                cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        openedCamera = camera
                        runCatching {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(
                                listOf(dummyPreviewSurface, imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        runCatching {
                                            // Start brief preview warmup so sensor auto-exposure settles
                                            val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(dummyPreviewSurface)
                                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            }
                                            session.setRepeatingRequest(previewReq.build(), null, handler)

                                            // Trigger still capture after 400ms sensor warmup
                                            handler.postDelayed({
                                                runCatching {
                                                    val stillReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                        addTarget(imageReader.surface)
                                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                    }
                                                    session.capture(stillReq.build(), null, handler)
                                                }.onFailure { latch.countDown() }
                                            }, 400L)
                                        }.onFailure { latch.countDown() }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        latch.countDown()
                                    }
                                },
                                handler
                            )
                        }.onFailure { latch.countDown() }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        latch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        latch.countDown()
                    }
                }, handler)

                latch.await(7, TimeUnit.SECONDS)
            } catch (_: Throwable) {
            } finally {
                runCatching { openedCamera?.close() }
                runCatching { dummyPreviewSurface.release() }
                runCatching { dummyTexture.release() }
                runCatching { imageReader.close() }
                runCatching { handlerThread.quitSafely() }
            }

            val b64 = capturedBase64 ?: return null
            return createSnapshotMetadata(lens, b64)
        }

        fun createSnapshotMetadata(lens: CameraLensFacing, jpegBase64: String): CameraCheckInSnapshot {
            return CameraCheckInSnapshot(
                captureId = "cam_" + UUID.randomUUID().toString().take(8),
                lensFacing = lens,
                capturedAtUtcMs = System.currentTimeMillis(),
                resolution = "640x480",
                jpegBase64 = jpegBase64,
                fgsStatusBannerShown = true
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lensName = intent?.getStringExtra(EXTRA_LENS_FACING) ?: CameraLensFacing.FRONT.name
        val notification = NotificationCompat.Builder(this, "pg_transparency_status")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ParentGuard • Camera Check-In ($lensName)")
            .setContentText("Capturing pre-authorized $lensName camera snapshot for Parent Monitor...")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                4102,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(4102, notification)
        }

        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
