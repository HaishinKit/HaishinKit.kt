package com.haishinkit.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.haishinkit.stream.StreamSession

class PlaybackViewModel(
    application: Application,
) : AndroidViewModel(application) {
    var session: StreamSession by mutableStateOf(
        StreamSession
            .Builder(application.applicationContext, Preference.shared.toStreamUri())
            .setMode(StreamSession.Mode.PLAYBACK)
            .build(),
    )
        private set

    private var sessionUri = Preference.shared.toStreamUri()

    suspend fun refreshSession() {
        val uri = Preference.shared.toStreamUri()
        if (uri == sessionUri) return
        session.close()
        session.stream.dispose()
        session =
            StreamSession.Builder(getApplication<Application>().applicationContext, uri)
                .setMode(StreamSession.Mode.PLAYBACK).build()
        sessionUri = uri
    }
}
