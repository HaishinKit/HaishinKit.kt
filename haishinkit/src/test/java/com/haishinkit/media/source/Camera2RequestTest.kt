package com.haishinkit.media.source

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraDeviceImpl
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [30],
    shadows = [RequestBuilder::class, RecordedRequest::class, RecordingSession::class, ConfiguringCamera::class],
)
class Camera2RequestTest {
    @Test
    fun firstRequestsAfterCreationAndRecreationContainTorchAndZoomRatio() {
        verifyFirstRequests(useRatio = true)
    }

    @Test
    @Config(sdk = [28])
    fun firstRequestsClampDigitalZoomAndCenterTheCrop() {
        verifyFirstRequests(useRatio = false)
    }

    @Test
    @Config(sdk = [27])
    fun legacySessionCreationAlsoAppliesPresetControls() {
        verifyFirstRequests(useRatio = false)
    }

    private fun verifyFirstRequests(useRatio: Boolean) =
        runBlocking {
            RecordingSession.requests.clear()
            val context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
            val metadata = Shadow.extract<ShadowCameraCharacteristics>(characteristics)
            metadata.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
            metadata.set(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 4f)
            metadata.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(100, 200, 4100, 3200))
            if (useRatio) metadata.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(0.5f, 8f))
            shadowOf(manager).addCamera("0", characteristics)
            val source =
                Camera2Source(context).apply {
                    isTorchEnabled = true
                    zoomRatio = if (useRatio) 2f else 10f
                }
            val output = Camera2Output(context, source, "0")
            ReflectionHelpers.setField(source, "output", output)
            val texture = SurfaceTexture(0)
            val surface = Surface(texture)
            try {
                assertTrue(output.open().isSuccess)
                var previousSession: CameraCaptureSession? = null
                repeat(2) {
                    output.createCaptureSession(surface)
                    val (session, request) =
                        checkNotNull(RecordingSession.requests.poll(5, TimeUnit.SECONDS)) {
                            "Session did not submit its first repeating request."
                        }
                    assertNotSame(previousSession, session)
                    assertEquals(CaptureRequest.FLASH_MODE_TORCH, request[CaptureRequest.FLASH_MODE])
                    if (useRatio) {
                        assertEquals(2f, request[CaptureRequest.CONTROL_ZOOM_RATIO])
                    } else {
                        assertEquals(Rect(1600, 1325, 2600, 2075), request[CaptureRequest.SCALER_CROP_REGION])
                        assertEquals(10f, source.zoomRatio)
                    }
                    previousSession = session
                }
                // Updating one control must retain the other in the submitted request.
                source.isTorchEnabled = false
                val torchOff = checkNotNull(RecordingSession.requests.poll(5, TimeUnit.SECONDS)).second
                assertEquals(CaptureRequest.FLASH_MODE_OFF, torchOff[CaptureRequest.FLASH_MODE])
                if (useRatio) assertEquals(2f, torchOff[CaptureRequest.CONTROL_ZOOM_RATIO])
                source.zoomRatio = 1f
                val zoomReset = checkNotNull(RecordingSession.requests.poll(5, TimeUnit.SECONDS)).second
                assertEquals(CaptureRequest.FLASH_MODE_OFF, zoomReset[CaptureRequest.FLASH_MODE])
                if (useRatio) {
                    assertEquals(1f, zoomReset[CaptureRequest.CONTROL_ZOOM_RATIO])
                } else {
                    assertEquals(Rect(100, 200, 4100, 3200), zoomReset[CaptureRequest.SCALER_CROP_REGION])
                }
            } finally {
                output.close()
                surface.release()
                texture.release()
                RecordingSession.requests.clear()
            }
        }
}

/** Preserve builder metadata in request snapshots; Robolectric's default builder only shadows set/get. */
@Implements(CaptureRequest.Builder::class)
class RequestBuilder {
    private val values = mutableMapOf<CaptureRequest.Key<*>, Any?>()

    @Implementation
    fun <T> set(
        key: CaptureRequest.Key<T>,
        value: T?,
    ) {
        values[key] = value
    }

    @Implementation
    fun build(): CaptureRequest =
        Shadow.newInstanceOf(CaptureRequest::class.java).also {
            Shadow.extract<RecordedRequest>(it).values = values.toMap()
        }
}

@Implements(CaptureRequest::class)
class RecordedRequest {
    var values: Map<CaptureRequest.Key<*>, Any?> = emptyMap()

    @Implementation
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: CaptureRequest.Key<T>): T? = values[key] as T?
}

@Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl", isInAndroidSdk = false)
class RecordingSession {
    @RealObject private lateinit var session: CameraCaptureSession

    @Implementation
    fun setRepeatingRequest(
        request: CaptureRequest,
        callback: CameraCaptureSession.CaptureCallback?,
        handler: Handler?,
    ): Int {
        requests.add(session to request)
        return 1
    }

    @Implementation
    fun close() {}

    companion object {
        val requests = LinkedBlockingQueue<Pair<CameraCaptureSession, CaptureRequest>>()
    }
}

/** Complete configuration deterministically, while exercising the production session callbacks. */
@Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
class ConfiguringCamera : ShadowCameraDeviceImpl() {
    @Implementation
    public override fun createCaptureSession(configuration: SessionConfiguration) {
        configuration.stateCallback.onConfigured(newSession())
    }

    @Implementation
    public override fun createCaptureSession(
        outputs: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?,
    ) {
        callback.onConfigured(newSession())
    }

    private fun newSession(): CameraCaptureSession = Shadow.newInstanceOf(Class.forName("android.hardware.camera2.impl.CameraCaptureSessionImpl")) as CameraCaptureSession
}
