package com.haishinkit.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import java.util.concurrent.CompletableFuture

class AudioCaptureTestService : Service() {
    private var active: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("audio-mix-test", "Audio capture test", NotificationManager.IMPORTANCE_LOW))
        startForeground(6201, Notification.Builder(this, "audio-mix-test").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("HaishinKit audio capture test").build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val result = requireNotNull(intent?.getParcelableExtra<Intent>("projection"))
            active = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, result)
            projection.complete(requireNotNull(active))
        } catch (cause: Exception) {
            projection.completeExceptionally(cause)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active?.stop()
        active = null
        super.onDestroy()
    }

    companion object {
        @Volatile var projection = CompletableFuture<MediaProjection>()
    }
}
