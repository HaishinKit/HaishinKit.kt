package com.haishinkit.app

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import com.haishinkit.stream.StreamSession

class PlaybackViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val session: StreamSession =
        StreamSession
            .Builder(application.applicationContext, Preference.shared.rtmpURL.toUri())
            .setMode(StreamSession.Mode.PLAYBACK)
            .build()
}
