package com.haishinkit.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.source.AudioRecordSource
import com.haishinkit.media.source.MediaProjectionSource
import com.haishinkit.stream.StreamSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class MediaProjectionService :
    Service(),
    CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    private val mixer: MediaMixer by lazy {
        MediaMixer(applicationContext).apply {
            registerOutput(session.stream)
        }
    }
    private val session: StreamSession by lazy {
        StreamSession.Builder(applicationContext, Preference.shared.rtmpURL.toUri()).build()
    }
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val localBroadcastManager: LocalBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(this)
    }
    private val messenger: Messenger by lazy {
        Messenger(handler)
    }
    private val isRunningReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
            }
        }
    private var handler =
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_CONNECT -> {
                        Log.i(TAG, "MSG_CONNECT")
                    }

                    MSG_CLOSE -> {
                        Log.i(TAG, "MSG_CLOSE")
                        launch {
                            session.close()
                        }
                        stopSelf()
                    }
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.i(TAG, "onStartCommand")
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel =
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            channel.description = CHANNEL_DESC
            channel.setSound(null, null)
            notificationManager.createNotificationChannel(channel)
        }
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .apply {
                setColorized(true)
                setSmallIcon(R.mipmap.ic_launcher)
                setStyle(NotificationCompat.DecoratedCustomViewStyle())
                setContentTitle(NOTIFY_TITLE)
            }.build()
            .apply {
                if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT) {
                    startForeground(ID, this, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(ID, this)
                }
            }
        launch {
            mixer.attachAudio(0, AudioRecordSource(applicationContext))
            intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)?.let {
                mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)?.let {
                    val source =
                        MediaProjectionSource(
                            applicationContext,
                            it,
                        )
                    mixer.attachVideo(0, source).onSuccess {
                        Log.i(TAG, "${source.video.videoSize}")
                        mixer.screen.frame =
                            Rect(0, 0, source.video.videoSize.width, source.video.videoSize.height)
                        session.connect()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        localBroadcastManager.registerReceiver(
            isRunningReceiver,
            IntentFilter(ACTION_SERVICE_RUNNING),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        mixer.dispose()
        localBroadcastManager.unregisterReceiver(isRunningReceiver)
    }

    companion object {
        private const val ACTION_SERVICE_RUNNING: String = "ACTION_SERVICE_RUNNING"

        const val ID = 1
        const val CHANNEL_ID = "MediaProjectionID"
        const val CHANNEL_NAME = "MediaProjectionService"
        const val CHANNEL_DESC = ""
        const val NOTIFY_TITLE = "Recording."
        const val MSG_CONNECT = 0
        const val MSG_CLOSE = 1
        const val MSG_SET_VIDEO_EFFECT = 2
        const val MSG_SET_MEDIA_PROJECTION = 3

        private const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        private const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private val TAG = MediaProjectionSource::class.java.simpleName

        fun startService(
            context: Context,
            resultCode: Int,
            resultData: Intent?,
        ) = Intent(context, MediaProjectionService::class.java)
            .apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }.apply {
                ContextCompat.startForegroundService(context, this)
                return this
            }

        fun isRunning(context: Context): Boolean = LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(ACTION_SERVICE_RUNNING))
    }
}
