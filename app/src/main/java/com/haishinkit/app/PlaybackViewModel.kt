package com.haishinkit.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.haishinkit.stream.StreamSession

class PlaybackViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val session: StreamSession =
        StreamSession
            .Builder(application.applicationContext, Preference.shared.toRtmpUrl())
            .setMode(StreamSession.Mode.PLAYBACK)
            .build()
}
