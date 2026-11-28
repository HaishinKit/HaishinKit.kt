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
import android.net.Uri
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
import com.haishinkit.rtc.RtcStream
import com.haishinkit.screen.AssetScreenObject
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.VideoScreenObject
import com.haishinkit.srt.SrtStream
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    var mainCameraSource: Camera2Source? by mutableStateOf(null)
        private set

    val recorder: MediaRecorder = MediaRecorder(application.applicationContext)
    val selectedCamera: StateFlow<CameraDevice?>
        get() = _selectedCamera.asStateFlow()
    var videoEffectItems: List<VideoEffectItem>

    internal var compositionSize by mutableStateOf(Size(Screen.DEFAULT_WIDTH, Screen.DEFAULT_HEIGHT))
        private set

    internal val publishingProtocol: PublishingProtocol
        get() =
            when (session?.stream) {
                is SrtStream -> PublishingProtocol.SRT
                is RtcStream -> PublishingProtocol.WEBRTC
                else -> PublishingProtocol.RTMP
            }

    internal val dynamicRangeMode = HdrPreference.selected(application).publishing

    internal var encoding by mutableStateOf(Preference.shared.encoding)
        private set

    internal val assetErrors: SharedFlow<String> get() = _assetErrors
    internal val ingestDiscovery = BonjourIngestDiscovery(application.applicationContext)
    internal val screenObjectDragController: ScreenObjectDragController

    internal var sceneFileBusy by mutableStateOf(false)
        private set
    internal var loadedSceneSize: Size? by mutableStateOf(null)
        private set

    private var cameraDeviceManager: CameraDeviceManager =
        CameraDeviceManager(context = application.applicationContext)
    private var mixer: MediaMixer = MediaMixer(application.applicationContext, dynamicRangeMode)
    private val _selectedCamera = MutableStateFlow<CameraDevice?>(null)
    private val cameraLock = Mutex()
    private val audioLock = Mutex()
    private val microphoneSource = AudioRecordSource(application.applicationContext).apply { sampleRate = 48_000 }
    private var frontCameraObject: VideoScreenObject? = null
    private var sessionSettings: Preference.SessionSettings? = null

    private val _assetErrors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var assetPlaybackEnabled = false
    private val pausedAssets = mutableSetOf<AssetScreenObject>()
    private var currentVideoEffect: VideoEffect? = null
    private val sceneFiles = SampleSceneFiles(application.applicationContext)

    init {
        val _videoEffectItems = mutableListOf<VideoEffectItem>()
        _videoEffectItems.add(VideoEffectItem("Normal", null))
        if (dynamicRangeMode == com.haishinkit.graphics.DynamicRangeMode.SDR) {
            _videoEffectItems.add(VideoEffectItem("Monochrome", MonochromeVideoEffect()))
            _videoEffectItems.add(VideoEffectItem("Mosaic", MosaicVideoEffect()))
            _videoEffectItems.add(VideoEffectItem("Sepia", SepiaVideoEffect()))
        }
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
        if (dynamicRangeMode == com.haishinkit.graphics.DynamicRangeMode.SDR) mixer.registerOutput(recorder)
        RendererPreference.register(this)
    }

    internal suspend fun saveSceneFile(uri: Uri): Result<Unit> =
        sceneOperation {
            val state = sceneFiles.captureNextFrame(mixer.screen)
            withContext(Dispatchers.IO) { sceneFiles.write(uri, sceneFiles.encode(state)) }
        }

    internal suspend fun restoreSceneFile(uri: Uri): Result<Unit> =
        sceneOperation {
            var pending: SampleSceneFiles.Loaded? = null
            var installed = false
            var preparedContainer: ScreenObjectContainer? = null
            try {
                withContext(Dispatchers.IO) { pending = sceneFiles.read(uri) }
                val loaded = checkNotNull(pending)
                cameraLock.withLock {
                    check(!recorder.isRecording && (session?.readyState?.value ?: StreamSession.ReadyState.CLOSED) == StreamSession.ReadyState.CLOSED) { "Stop publishing and recording before restoring a scene." }
                    val container =
                        sceneFiles.create(loaded).also {
                            preparedContainer = it
                            AssetScreenObject.findAssets(it).forEach { asset ->
                                asset.onError = ::reportAssetPlaybackError
                                mixer.attachAudio(asset.audioTrack, asset.audioSource).getOrThrow()
                            }
                        }
                    val hadFront = frontCameraObject != null
                    val restoredFront = container.findByClass(VideoScreenObject::class.java).filterNot { it is AssetScreenObject }.firstOrNull { it.track == FRONT_CAMERA_TRACK }
                    if (hadFront && restoredFront == null) {
                        val camera = checkNotNull(_selectedCamera.value)
                        val source = mainCameraSource.replacement(application.applicationContext, camera.id)
                        mixer.attachVideos(mapOf(0 to source, FRONT_CAMERA_TRACK to null)).getOrThrow()
                        source.copyRequestedControlsFrom(mainCameraSource)
                        mainCameraSource = source
                    }
                    mixer.screen.findByClass(LottieScreen::class.java).forEach { it.cancelAnimation() }
                    AssetScreenObject.findAssets(mixer.screen).forEach { it.close() }
                    pausedAssets.clear()
                    if (!assetPlaybackEnabled) {
                        AssetScreenObject.findAssets(container).forEach { asset ->
                            if (asset.isReading) pausedAssets.add(asset)
                            asset.cancelReading()
                        }
                    }
                    val liveVideos = mixer.screen.findByClass(VideoScreenObject::class.java).filterNot { it is AssetScreenObject }.associateBy { it.track }
                    container.findByClass(VideoScreenObject::class.java).filterNot { it is AssetScreenObject }.forEach { video ->
                        val previous = liveVideos[video.track]
                        video.isRotatesWithContent = video.track == 0 || previous?.isRotatesWithContent == true
                        video.deviceOrientation = previous?.deviceOrientation ?: application.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation
                        if (video.track == 0) currentVideoEffect?.let { video.videoEffect = it }
                    }
                    val size = Size(loaded.snapshot.size.width, loaded.snapshot.size.height)
                    mixer.screen.frame = Rect(0, 0, size.width, size.height)
                    mixer.screen.transition(container)
                    container.findByClass(LottieScreen::class.java).forEach { it.playAnimation() }
                    frontCameraObject = if (hadFront) restoredFront else null
                    loadedSceneSize = size
                    compositionSize = size

                    // The editor works on direct children; nested containers move/delete as groups.
                    screenObjectDragController.replaceObjects(sceneFiles.editableObjects(container))
                    sceneFiles.install(loaded)
                    installed = true
                }
            } finally {
                if (!installed) preparedContainer?.let { AssetScreenObject.findAssets(it) }?.forEach { it.close() }
                if (!installed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { pending?.directory?.deleteRecursively() }
            }
        }

    internal suspend fun selectIngest(endpoint: IngestEndpoint): Result<Unit> {
        if (session != null && session?.readyState?.value != StreamSession.ReadyState.CLOSED) {
            return Result.failure(IllegalStateException("Stop publishing before changing the destination."))
        }
        val previous = Preference.shared
        Preference.shared = previous.copy(streamURL = endpoint.uri)
        val result = refreshSession()
        if (result.isFailure) Preference.shared = previous
        return result
    }

    suspend fun refreshSession(): Result<Unit> {
        var replacement: StreamSession? = null
        try {
            val requested = Preference.shared.sessionSettings()
            require(dynamicRangeMode != com.haishinkit.graphics.DynamicRangeMode.HLG || requested.uri.scheme in setOf("rtmp", "rtmps")) { application.getString(R.string.studio_hdr_rtmp_only) }
            val settings = requested.copy(encoding = requested.encoding?.forDynamicRange(dynamicRangeMode))
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
            encoding = checkNotNull(settings.encoding)
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

    internal fun preparePublishing(): Result<Unit> =
        runCatching {
            val current = checkNotNull(session) { "The stream is not ready." }
            check(current.readyState.value == StreamSession.ReadyState.CLOSED) { "The stream is already connecting or publishing." }
            encoding.applyTo(current.stream, publishingProtocol, videoEnabled = current.stream.hasVideo, audioEnabled = current.stream.hasAudio)
        }

    internal fun updateEncoding(settings: PublishEncodingSettings): Result<Unit> =
        runCatching {
            val current = checkNotNull(session) { "The stream is not ready." }
            val codecChanged = settings.videoCodec != encoding.videoCodec || settings.audioCodec != encoding.audioCodec
            check(!codecChanged || current.readyState.value == StreamSession.ReadyState.CLOSED) { "Stop publishing before changing codecs." }
            val previous = encoding
            try {
                settings.applyTo(current.stream, publishingProtocol, validateFormat = current.readyState.value == StreamSession.ReadyState.CLOSED)
            } catch (cause: Exception) {
                runCatching { previous.applyTo(current.stream, publishingProtocol, validateFormat = current.readyState.value == StreamSession.ReadyState.CLOSED) }
                throw cause
            }
            Preference.shared.encoding = Preference.shared.encoding.mergingChanges(previous, settings)
            encoding = settings
            sessionSettings = sessionSettings?.copy(encoding = settings)
        }

    internal fun addScreenObject(
        kind: ExampleScreenObjectKind,
        position: Offset,
        onError: (String) -> Unit,
    ) {
        if (sceneFileBusy) return
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

                ExampleScreenObjectKind.VIDEO -> {
                    viewModelScope.launch {
                        runCatching {
                            val file = withContext(Dispatchers.IO) { SampleVideo.file(application) }
                            if (sceneFileBusy) return@launch
                            if (AssetScreenObject.findAssets(mixer.screen).size >= 4) {
                                onError(application.getString(R.string.asset_limit))
                                return@launch
                            }
                            val asset =
                                AssetScreenObject(application.applicationContext).apply {
                                    frame = Rect(0, 0, 360, 240)
                                    isLooping = true
                                    this.onError = ::reportAssetPlaybackError
                                    startReading(Uri.fromFile(file))
                                }
                            var inserted = false
                            try {
                                mixer.attachAudio(asset.audioTrack, asset.audioSource).getOrThrow()
                                if (sceneFileBusy) return@launch
                                if (AssetScreenObject.findAssets(mixer.screen).size >= 4) {
                                    onError(application.getString(R.string.asset_limit))
                                    return@launch
                                }
                                if (!assetPlaybackEnabled) {
                                    asset.cancelReading()
                                    pausedAssets.add(asset)
                                }
                                screenObjectDragController.register(asset, position)
                                mixer.screen.addChild(asset)
                                inserted = true
                            } finally {
                                if (!inserted) {
                                    pausedAssets.remove(asset)
                                    mixer.screen.removeChild(asset)
                                    screenObjectDragController.unregister(asset)
                                    asset.close()
                                }
                            }
                        }.onFailure { if (it is CancellationException) throw it else onError(application.getString(R.string.asset_playback_error)) }
                    }
                    return
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
        val activeFront = frontCameraObject
        if (activeFront != null && (target === activeFront || (target is ScreenObjectContainer && activeFront in target.findByClass(VideoScreenObject::class.java)))) {
            viewModelScope.launch {
                cameraLock.withLock {
                    if (activeFront !== frontCameraObject) return@withLock
                    val camera = _selectedCamera.value ?: return@withLock
                    val source = mainCameraSource.replacement(application.applicationContext, camera.id)
                    mixer.attachVideos(mapOf(0 to source, FRONT_CAMERA_TRACK to null)).onSuccess {
                        source.copyRequestedControlsFrom(mainCameraSource)
                        mainCameraSource = source
                        frontCameraObject = null
                        cancelAnimations(target)
                        mixer.screen.removeChild(target)
                        screenObjectDragController.unregister(target)
                    }
                }
            }
            return
        }
        cancelAnimations(target)
        mixer.screen.removeChild(target)
        screenObjectDragController.unregister(target)
    }

    fun selectAudioDevice() {
        viewModelScope.launch {
            audioLock.withLock {
                mixer.attachAudio(0, microphoneSource)
            }
        }
    }

    fun setVideoEffect(videoEffect: VideoEffect) {
        currentVideoEffect = videoEffect
        mixer.setVideoEffect(0, videoEffect)
    }

    fun selectCameraDevice(
        cameraDevice: CameraDevice?,
        retry: Boolean = true,
    ) {
        viewModelScope.launch {
            cameraLock.withLock {
                if (!retry && cameraDevice == _selectedCamera.value && mainCameraSource != null) return@withLock
                val context = application.applicationContext
                val permissionGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val source = if (permissionGranted) cameraDevice?.let { Camera2Source(context, it.id) } else null
                mixer.attachVideos(mapOf(0 to source, FRONT_CAMERA_TRACK to null)).onSuccess {
                    _selectedCamera.value = cameraDevice
                    mainCameraSource = source
                    removeFrontCameraObject()
                }
            }
        }
    }

    fun onConfigurationChanged(configuration: Configuration) {
        val composition =
            loadedSceneSize ?: when (configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> Size(Screen.DEFAULT_HEIGHT, Screen.DEFAULT_WIDTH)
                Configuration.ORIENTATION_LANDSCAPE -> Size(Screen.DEFAULT_WIDTH, Screen.DEFAULT_HEIGHT)
                else -> return
            }
        mixer.screen.frame = Rect(0, 0, composition.width, composition.height)
        compositionSize = composition
        if (loadedSceneSize == null) screenObjectDragController.fitObjectsToScreen(composition)
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
        assetPlaybackEnabled = true
        val current = AssetScreenObject.findAssets(mixer.screen).toSet()
        pausedAssets.filter { it in current }.forEach { asset -> asset.sourceUri?.let { asset.startReading(it) } }
        pausedAssets.clear()
        viewModelScope.launch {
            for (asset in current) {
                if (!assetPlaybackEnabled) return@launch
                mixer.attachAudio(asset.audioTrack, asset.audioSource).onFailure(::reportAssetPlaybackError)
            }
            if (assetPlaybackEnabled) mixer.startRunning()
        }
        DebugFrameCapture.attach(this, mixer.screen)
    }

    fun captureQuality(context: Context) {
        QualityCapture.save(context, mixer.screen, "camera")
    }

    fun stopRunning() {
        assetPlaybackEnabled = false
        AssetScreenObject.findAssets(mixer.screen).forEach { asset ->
            if (asset.isReading) pausedAssets.add(asset)
            asset.cancelReading()
        }
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
        AssetScreenObject.findAssets(mixer.screen).forEach { it.close() }
        pausedAssets.clear()
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
        ingestDiscovery.stop()
        DebugFrameCapture.detach(this)
        session?.stream?.dispose()
        RendererPreference.unregister(this)
        super.onCleared()
        cameraDeviceManager.release()
        AssetScreenObject.findAssets(mixer.screen).forEach { it.close() }
        pausedAssets.clear()
        mixer.dispose()
        sceneFiles.close()
    }

    private fun reportAssetPlaybackError(cause: Throwable) {
        android.util.Log.w("SampleAsset", "Video playback failed", cause)
        _assetErrors.tryEmit(application.getString(R.string.asset_playback_error))
    }

    private suspend fun sceneOperation(block: suspend () -> Unit): Result<Unit> {
        if (sceneFileBusy) return Result.failure(IllegalStateException("A scene file operation is already running."))
        if (recorder.isRecording || session?.readyState?.value?.let { it != StreamSession.ReadyState.CLOSED } == true) {
            return Result.failure(IllegalStateException("Stop publishing and recording before changing scene files."))
        }
        sceneFileBusy = true
        return try {
            block()
            Result.success(Unit)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            Result.failure(cause)
        } finally {
            sceneFileBusy = false
        }
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

        val restoredOverlay = mixer.screen.findByClass(VideoScreenObject::class.java).firstOrNull { it.track == FRONT_CAMERA_TRACK }
        val overlay =
            restoredOverlay ?: VideoScreenObject().apply {
                cornerRadius = 22f
                track = FRONT_CAMERA_TRACK
                frame = Rect(0, 0, 240, 180)
            }
        val maximumSize = Size(1280, 720)
        val mainSource = mainCameraSource.replacement(context, mainCamera.id, maximumSize)
        val result =
            mixer.attachVideos(
                mapOf(
                    0 to mainSource,
                    FRONT_CAMERA_TRACK to Camera2Source(context, frontCamera.id).apply { this.maximumSize = maximumSize },
                ),
            )
        if (result.isFailure) return "Could not start the front camera."

        mainSource.copyRequestedControlsFrom(mainCameraSource)
        mainCameraSource = mainSource
        frontCameraObject = overlay
        if (restoredOverlay == null) {
            screenObjectDragController.register(overlay, position)
            mixer.screen.addChild(overlay)
        }
        return null
    }

    private fun cancelAnimations(target: ScreenObject) {
        (target as? AssetScreenObject)?.let {
            pausedAssets.remove(it)
            it.close()
        }
        (target as? ScreenObjectContainer)?.findByClass(AssetScreenObject::class.java)?.forEach {
            pausedAssets.remove(it)
            it.close()
        }
        (target as? LottieScreen)?.cancelAnimation()
        (target as? ScreenObjectContainer)?.findByClass(LottieScreen::class.java)?.forEach { it.cancelAnimation() }
    }

    private fun removeFrontCameraObject() {
        val overlay = frontCameraObject ?: return
        frontCameraObject = null
        // A nested placeholder belongs to its group; keep its saved layout when capture stops.
        if (overlay in mixer.screen.getChildren()) {
            mixer.screen.removeChild(overlay)
            screenObjectDragController.unregister(overlay)
        }
    }

    private companion object {
        const val FRONT_CAMERA_TRACK = 1
    }
}
