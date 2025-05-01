package com.haishinkit.app

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.haishinkit.graphics.effect.BicubicVideoEffect
import com.haishinkit.graphics.effect.BilinearVideoEffect
import com.haishinkit.graphics.effect.DefaultVideoEffect
import com.haishinkit.graphics.effect.LanczosVideoEffect
import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpStream

class MediaProjectionTabFragment :
    Fragment(),
    ServiceConnection {
    private var messenger: Messenger? = null
    private val startMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                MediaProjectionService.data = result.data
                Intent(activity, MediaProjectionService::class.java).also { intent ->
                    if (Build.VERSION_CODES.O <= Build.VERSION.SDK_INT) {
                        activity?.startForegroundService(intent)
                    } else {
                        activity?.startService(intent)
                    }
                    activity?.bindService(
                        intent,
                        this@MediaProjectionTabFragment,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                Log.i(toString(), "mediaProjectionManager success")
            }
        }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val v = inflater.inflate(R.layout.fragment_mediaprojection, container, false)
        val button = v.findViewById<Button>(R.id.button)
        MediaProjectionService.listener =
            object : RtmpStream.Listener {
                override fun onStatics(
                    stream: RtmpStream,
                    connection: RtmpConnection,
                ) {
                    activity?.runOnUiThread {
                        v.findViewById<TextView>(R.id.fps).text = "${stream.currentFPS}FPS"
                    }
                }
            }
        val filter = v.findViewById<Button>(R.id.filter_button)
        filter.setOnClickListener {
            when (filter.text) {
                "Normal" -> {
                    messenger?.send(
                        Message.obtain(
                            null,
                            MediaProjectionService.MSG_SET_VIDEO_EFFECT,
                            BicubicVideoEffect(),
                        ),
                    )
                    filter.text = "Bicubic"
                }

                "Bicubic" -> {
                    messenger?.send(
                        Message.obtain(
                            null,
                            MediaProjectionService.MSG_SET_VIDEO_EFFECT,
                            BilinearVideoEffect(),
                        ),
                    )
                    filter.text = "Bilinear"
                }

                "Bilinear" -> {
                    messenger?.send(
                        Message.obtain(
                            null,
                            MediaProjectionService.MSG_SET_VIDEO_EFFECT,
                            LanczosVideoEffect(),
                        ),
                    )
                    filter.text = "Lanczos"
                }

                else -> {
                    messenger?.send(
                        Message.obtain(
                            null,
                            MediaProjectionService.MSG_SET_VIDEO_EFFECT,
                            DefaultVideoEffect.shared,
                        ),
                    )
                    filter.text = "Normal"
                }
            }
        }

        button.setOnClickListener {
            when (button.text) {
                "Publish" -> {
                    if (messenger == null) {
                        val mediaProjectionManager =
                            activity?.getSystemService(Service.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CONNECT))
                    }
                    button.text = "Stop"
                }

                else -> {
                    messenger?.send(Message.obtain(null, MediaProjectionService.MSG_CLOSE))
                    button.text = "Publish"
                }
            }
        }
        return v
    }

    override fun onServiceConnected(
        name: ComponentName?,
        binder: IBinder?,
    ) {
        messenger = Messenger(binder)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        messenger = null
    }

    companion object {
        fun newInstance(): MediaProjectionTabFragment = MediaProjectionTabFragment()

        private val TAG = MediaProjectionTabFragment::class.java.simpleName
    }
}
