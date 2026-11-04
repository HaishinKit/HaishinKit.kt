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
    var session: StreamSession? by mutableStateOf(null)
        private set
    var sessionError: String? by mutableStateOf(null)
        private set

    private var sessionUri: android.net.Uri? = null

    suspend fun refreshSession(): Result<Unit> {
        var replacement: StreamSession? = null
        try {
            val uri = Preference.shared.toStreamUri()
            if (uri == sessionUri && session != null) {
                sessionError = null
                return Result.success(Unit)
            }
            val candidate =
                StreamSession.Builder(getApplication<Application>().applicationContext, uri)
                    .setMode(StreamSession.Mode.PLAYBACK).build()
            replacement = candidate
            session?.let { previous ->
                previous.close()
                previous.stream.dispose()
            }
            session = candidate
            sessionUri = uri
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
