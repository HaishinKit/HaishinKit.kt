package com.haishinkit.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.haishinkit.device.CameraDevice
import com.haishinkit.device.CameraDeviceManager
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaRecorder
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.screen.scene.SceneManager
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

class CameraViewModel(
    application: Application,
) : AndroidViewModel(application), DefaultLifecycleObserver, RendererChangeParticipant {
    var session: StreamSession? by mutableStateOf(null)
        private set
    var sessionError: String? by mutableStateOf(null)
        private set
    val cameraList: StateFlow<List<CameraDevice>>
        get() {
            return cameraDeviceManager.deviceList
        }

    val recorder: MediaRecorder = MediaRecorder(application.applicationContext)
    val selectedCamera: StateFlow<CameraDevice?>
        get() = _selectedCamera.asStateFlow()
    var videoEffectItems: List<VideoEffectItem>

    internal val screenObjectDragController: ScreenObjectDragController

    private var cameraDeviceManager: CameraDeviceManager =
        CameraDeviceManager(context = application.applicationContext)
    private var mixer: MediaMixer = MediaMixer(application.applicationContext)
    private val _selectedCamera = MutableStateFlow<CameraDevice?>(null)
    private val cameraLock = Mutex()
    private val audioLock = Mutex()
    private var frontCameraObject: VideoScreenObject? = null
    private var sessionSettings: Preference.SessionSettings? = null

    private val sceneManager: SceneManager by lazy {
        val manager = SceneManager(mixer.screen)
        manager.register(LottieScreen.TYPE) { data ->
            LottieScreen(application.applicationContext)
        }
        manager
    }

    init {
        val _videoEffectItems = mutableListOf<VideoEffectItem>()
        _videoEffectItems.add(VideoEffectItem("Normal", null))
        _videoEffectItems.add(VideoEffectItem("Monochrome", MonochromeVideoEffect()))
        _videoEffectItems.add(VideoEffectItem("Mosaic", MosaicVideoEffect()))
        _videoEffectItems.add(VideoEffectItem("Sepia", SepiaVideoEffect()))
        this.videoEffectItems = _videoEffectItems
        val cameras = cameraDeviceManager.getDeviceList()
        _selectedCamera.value = cameras.firstOrNull { it.position == CameraDevice.Position.BACK } ?: cameras.firstOrNull()

        val text = TextScreenObject()
        text.size = 60f
        text.value = "Hello World!!"
        text.layoutMargin.set(0, 0, 16, 16)
        text.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        text.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM

        val image = ImageScreenObject()
        image.bitmap =
            BitmapFactory.decodeResource(
                application.applicationContext.resources,
                R.drawable.game_jikkyou,
            )
        image.verticalAlignment = ScreenObject.VERTICAL_ALIGNMENT_BOTTOM
        image.frame.set(0, 0, 180, 180)

        mixer.screen.addChild(image)
        mixer.screen.addChild(text)

        val lottie = LottieScreen(application.applicationContext)
        lottie.setAnimation(R.raw.a1707149669115)
        lottie.frame.set(0, 0, 200, 200)
        lottie.horizontalAlignment = ScreenObject.HORIZONTAL_ALIGNMENT_RIGHT
        lottie.playAnimation()
        mixer.screen.addChild(lottie)

        screenObjectDragController = ScreenObjectDragController(mixer.screen, listOf(image, text, lottie))

        recorder.audioSetting.sampleRate = 48_000
        mixer.registerOutput(recorder)
        RendererPreference.register(this)
    }

    suspend fun refreshSession(): Result<Unit> {
        var replacement: StreamSession? = null
        try {
            val settings = Preference.shared.sessionSettings()
            if (settings == sessionSettings && session != null) {
                sessionError = null
                return Result.success(Unit)
            }
            val candidate = settings.create(application.applicationContext)
            replacement = candidate
            candidate.stream.audioSetting.sampleRate = 48_000
            session?.let { previous ->
                previous.close()
                mixer.unregisterOutput(previous.stream)
                previous.stream.dispose()
            }
            mixer.registerOutput(candidate.stream)
            session = candidate
            sessionSettings = settings
            replacement = null
            sessionError = null
            return Result.success(Unit)
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Exception) {
            sessionError = cause.message ?: "Check the stream URL."
            return Result.failure(cause)
        } finally {
            replacement?.stream?.dispose()
        }
    }

    internal fun addScreenObject(
        kind: ExampleScreenObjectKind,
        position: Offset,
        onError: (String) -> Unit,
    ) {
        val target =
            when (kind) {
                ExampleScreenObjectKind.TEXT ->
                    TextScreenObject().apply {
                        value = "New text"
                        size = 60f
                    }
                ExampleScreenObjectKind.IMAGE ->
                    ImageScreenObject().apply {
                        bitmap = BitmapFactory.decodeResource(application.applicationContext.resources, R.drawable.game_jikkyou)
                        frame = Rect(0, 0, 180, 180)
                    }
                ExampleScreenObjectKind.ANIMATION ->
                    LottieScreen(application.applicationContext).apply {
                        frame = Rect(0, 0, 200, 200)
                        setAnimation(R.raw.a1707149669115)
                        playAnimation()
                    }

                ExampleScreenObjectKind.FRONT_CAMERA -> {
                    viewModelScope.launch {
                        cameraLock.withLock { addFrontCamera(position) }?.let(onError)
                    }
                    return
                }
            }
        screenObjectDragController.register(target, position)
        mixer.screen.addChild(target)
    }

    internal fun removeFocusedScreenObject() {
        val target = screenObjectDragController.focusedObject ?: return
        if (target === frontCameraObject) {
            viewModelScope.launch {
                cameraLock.withLock {
                    if (target !== frontCameraObject) return@withLock
                    val camera = _selectedCamera.value ?: return@withLock
                    val source = Camera2Source(application.applicationContext, camera.id)
                    mixer.attachVideos(mapOf(0 to source, FRONT_CAMERA_TRACK to null)).onSuccess {
                        removeFrontCameraObject()
                    }
                }
            }
            return
        }
        (target as? LottieScreen)?.cancelAnimation()
        mixer.screen.removeChild(target)
        screenObjectDragController.unregister(target)
    }

    fun selectAudioDevice() {
        viewModelScope.launch {
            audioLock.withLock {
                if (mixer.hasAudio) return@withLock
                mixer.attachAudio(0, AudioRecordSource(application.applicationContext).apply { sampleRate = 48_000 })
            }
        }
    }

    fun setVideoEffect(videoEffect: VideoEffect) {
        mixer.setVideoEffect(0, videoEffect)
    }

    fun selectCameraDevice(
        cameraDevice: CameraDevice?,
        retry: Boolean = true,
    ) {
        viewModelScope.launch {
            cameraLock.withLock {
                if (!retry && cameraDevice == _selectedCamera.value && mixer.hasVideo) return@withLock
                val context = application.applicationContext
                val permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val source = if (permissionGranted) cameraDevice?.let { Camera2Source(context, it.id) } else null
                mixer.attachVideos(mapOf(0 to source, FRONT_CAMERA_TRACK to null)).onSuccess {
                    _selectedCamera.value = cameraDevice
                    removeFrontCameraObject()
                }
            }
        }
    }

    fun onConfigurationChanged(configuration: Configuration) {
        val composition =
            when (configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> Size(Screen.DEFAULT_HEIGHT, Screen.DEFAULT_WIDTH)
                Configuration.ORIENTATION_LANDSCAPE -> Size(Screen.DEFAULT_WIDTH, Screen.DEFAULT_HEIGHT)
                else -> return
            }
        mixer.screen.frame = Rect(0, 0, composition.width, composition.height)
        screenObjectDragController.fitObjectsToScreen(composition)
    }

    fun takeSnapShot(context: Context) {
        mixer.screen.readPixels {
            val bitmap = it ?: return@readPixels
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
            val path =
                MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bitmap,
                    "Title",
                    null,
                )
            val imageUri = path.toUri()
            val share = Intent(Intent.ACTION_SEND)
            share.type = "image/jpeg"
            share.putExtra(Intent.EXTRA_STREAM, imageUri)
            context.startActivity(Intent.createChooser(share, "Select"))
        }
    }

    fun startRunning() {
        mixer.startRunning()
        DebugFrameCapture.attach(this, mixer.screen)
    }

    fun captureQuality(context: Context) {
        QualityCapture.save(context, mixer.screen, "camera")
    }

    fun stopRunning() {
        QualityCapture.stop("camera")
        DebugFrameCapture.detach(this)
        mixer.stopRunning()
    }

    override fun onStart(owner: LifecycleOwner) {
        startRunning()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopRunning()
    }

    override suspend fun stopForRendererChange() {
        QualityCapture.stop("camera")
        DebugFrameCapture.detach(this)
        if (recorder.isRecording) recorder.stopRecording()
        mixer.stopRunning()
        mixer.unregisterOutput(recorder)
        session?.let { previous ->
            previous.close()
            mixer.unregisterOutput(previous.stream)
            previous.stream.dispose()
        }
        session = null
        sessionSettings = null
    }

    override fun onCleared() {
        DebugFrameCapture.detach(this)
        session?.stream?.dispose()
        RendererPreference.unregister(this)
        super.onCleared()
        cameraDeviceManager.release()
        mixer.dispose()
    }

    private suspend fun addFrontCamera(position: Offset): String? {
        if (frontCameraObject != null) return "A front camera is already on the screen."
        val context = application.applicationContext
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "Allow camera access to add the front camera."
        }
        val mainCamera = _selectedCamera.value
        if (mainCamera?.position != CameraDevice.Position.BACK) {
            return "Select a back camera before adding the front camera."
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Concurrent cameras require Android 11 or later."

        val manager = context.getSystemService(CameraManager::class.java)
        val combinations =
            try {
                manager.concurrentCameraIds
            } catch (_: CameraAccessException) {
                return "Could not check camera availability."
            }
        val frontCamera =
            cameraList.value.firstOrNull { camera ->
                camera.position == CameraDevice.Position.FRONT &&
                    combinations.any { mainCamera.id in it && camera.id in it }
            } ?: return "This back camera cannot run at the same time as a front camera."

        val overlay =
            VideoScreenObject().apply {
                cornerRadius = 22f
                track = FRONT_CAMERA_TRACK
                frame = Rect(0, 0, 240, 180)
            }
        val maximumSize = Size(1280, 720)
        val result =
            mixer.attachVideos(
                mapOf(
                    0 to Camera2Source(context, mainCamera.id).apply { this.maximumSize = maximumSize },
                    FRONT_CAMERA_TRACK to Camera2Source(context, frontCamera.id).apply { this.maximumSize = maximumSize },
                ),
            )
        if (result.isFailure) return "Could not start the front camera."

        frontCameraObject = overlay
        screenObjectDragController.register(overlay, position)
        mixer.screen.addChild(overlay)
        return null
    }

    private fun removeFrontCameraObject() {
        val overlay = frontCameraObject ?: return
        frontCameraObject = null
        mixer.screen.removeChild(overlay)
        screenObjectDragController.unregister(overlay)
    }

    private companion object {
        const val FRONT_CAMERA_TRACK = 1
    }
}
