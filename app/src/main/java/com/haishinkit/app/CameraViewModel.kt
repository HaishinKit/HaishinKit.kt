package com.haishinkit.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.haishinkit.device.CameraDevice
import com.haishinkit.device.DeviceManager
import com.haishinkit.graphics.effect.MonochromeVideoEffect
import com.haishinkit.graphics.effect.MosaicVideoEffect
import com.haishinkit.graphics.effect.SepiaVideoEffect
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.lottie.LottieScreen
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaRecorder
import com.haishinkit.media.source.AudioContinuousSource
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.Camera2Source
import com.haishinkit.screen.ImageScreenObject
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.TextScreenObject
import com.haishinkit.screen.scene.SceneManager
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CameraViewModel(
    application: Application,
) : AndroidViewModel(application), DefaultLifecycleObserver {
    private var deviceManager: DeviceManager =
        DeviceManager(context = application.applicationContext)
    private var mixer: MediaMixer = MediaMixer(application.applicationContext)
    val session: StreamSession =
        StreamSession
            .Builder(application.applicationContext, Preference.shared.toRtmpUrl())
            .build()
    val cameraList: StateFlow<List<CameraDevice>>
        get() {
            return deviceManager.cameraList
        }

    val recorder: MediaRecorder = MediaRecorder(application.applicationContext)
    private val _selectedCamera = MutableStateFlow<CameraDevice?>(null)
    val selectedCamera = _selectedCamera.asStateFlow()
    var videoEffectItems: List<VideoEffectItem>

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
        _selectedCamera.value = deviceManager.getCameraList().first()

        val text = TextScreenObject()
        text.size = 60f
        text.value = "Hello World!!"
        text.layoutMargins.set(0, 0, 16, 16)
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

        mixer.registerOutput(recorder)
        mixer.registerOutput(session.stream)
    }

    fun selectAudioDevice() {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mixer.attachAudio(0, AudioContinuousSource(application.applicationContext))
            } else {
                mixer.attachAudio(0, AudioRecordSource(application.applicationContext))
            }
        }
    }

    fun setVideoEffect(videoEffect: VideoEffect) {
        mixer.setVideoEffect(0, videoEffect)
    }

    fun selectCameraDevice(cameraDevice: CameraDevice?) {
        _selectedCamera.value = cameraDevice
        viewModelScope.launch {
            if (cameraDevice == null) {
                mixer.attachVideo(0, null)
            } else {
                mixer.attachVideo(0, Camera2Source(application.applicationContext, cameraDevice.id))
            }
        }
    }

    fun onConfigurationChanged(configuration: Configuration) {
        when (configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> {
                mixer.screen.frame =
                    Rect(
                        0,
                        0,
                        Screen.DEFAULT_HEIGHT,
                        Screen.DEFAULT_WIDTH,
                    )
            }

            Configuration.ORIENTATION_LANDSCAPE -> {
                mixer.screen.frame =
                    Rect(
                        0,
                        0,
                        Screen.DEFAULT_WIDTH,
                        Screen.DEFAULT_HEIGHT,
                    )
            }
        }
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
    }

    fun stopRunning() {
        mixer.stopRunning()
    }

    override fun onResume(owner: LifecycleOwner) {
        mixer.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        mixer.onPause(owner)
    }

    override fun onCleared() {
        Log.d("TAG", "onCleared")
        super.onCleared()
        deviceManager.release()
        mixer.dispose()
    }
}
