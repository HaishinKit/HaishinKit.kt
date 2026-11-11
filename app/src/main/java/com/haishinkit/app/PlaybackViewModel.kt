package com.haishinkit.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.haishinkit.stream.StreamSession

class PlaybackViewModel(
    application: Application,
) : AndroidViewModel(application), RendererChangeParticipant {
    var session: StreamSession? by mutableStateOf(null)
        private set
    var sessionError: String? by mutableStateOf(null)
        private set

    private var sessionSettings: Preference.SessionSettings? = null

    init {
        RendererPreference.register(this)
    }

    override suspend fun stopForRendererChange() {
        session?.let { previous ->
            previous.close()
            previous.stream.dispose()
        }
        session = null
        sessionSettings = null
    }

    override fun onCleared() {
        session?.stream?.dispose()
        RendererPreference.unregister(this)
        super.onCleared()
    }

    suspend fun refreshSession(): Result<Unit> {
        var replacement: StreamSession? = null
        try {
            val settings = Preference.shared.sessionSettings(StreamSession.Mode.PLAYBACK)
            if (settings == sessionSettings && session != null) {
                sessionError = null
                return Result.success(Unit)
            }
            val candidate = settings.create(getApplication<Application>().applicationContext)
            replacement = candidate
            session?.let { previous ->
                previous.close()
                previous.stream.dispose()
            }
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
}
