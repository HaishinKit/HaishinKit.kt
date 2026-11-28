package com.haishinkit.media.source

import android.content.Context
import android.net.Uri
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.audio.PcmAudioConsumer
import com.haishinkit.media.audio.PcmAudioFormat
import com.haishinkit.media.audio.PushAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Audio exists only when explicitly attached to a mixer; no local AudioTrack is opened. */
internal class AssetAudioSource(private val context: Context, private val onError: (Throwable) -> Unit) : AudioSource, PushAudioSource {
    @Volatile override var isMuted = false
    override val isClosed: Boolean get() = closed
    override var onEnded: (() -> Unit)? = null
        @Synchronized set(value) {
            field = value
            if (closed) value?.invoke()
        }

    @Volatile override var consumer: PcmAudioConsumer? = null
        set(value) {
            synchronized(this) {
                if (field !== value) field?.clear()
                field = value
                restart()
            }
        }
    private var opened = false

    @Volatile private var closed = false
    private var uri: Uri? = null
    private var clock: AssetPlaybackClock? = null
    private var decoder: AssetAudioDecoder? = null
    private var retiring: AssetAudioDecoder? = null
    private val empty = ByteBuffer.allocateDirect(0)

    override suspend fun open(mixer: MediaMixer): Result<Unit> =
        runCatching {
            synchronized(this) {
                check(!closed) { "AssetScreenObject is closed" }
                opened = true
                restart()
            }
        }

    override suspend fun close(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val previous =
                synchronized(this@AssetAudioSource) {
                    opened = false
                    stopDecoder()
                    retiring
                }
            previous?.awaitRelease()
            Result.success(Unit)
        }

    override fun read(track: Int) = MediaBuffer(MediaType.AUDIO, track, empty)

    @Synchronized fun bind(
        uri: Uri,
        clock: AssetPlaybackClock,
    ) {
        this.uri = uri
        this.clock = clock
        restart()
    }

    @Synchronized fun unbind(clock: AssetPlaybackClock) {
        if (this.clock !== clock) return
        this.clock = null
        stopDecoder()
        consumer?.clear()
    }

    @Synchronized fun finish() {
        if (closed) return
        closed = true
        opened = false
        stopDecoder()
        consumer?.clear()
        onEnded?.invoke()
    }

    private fun stopDecoder() {
        decoder?.let {
            it.cancel()
            retiring = it
        }
        decoder = null
    }

    private fun restart() {
        stopDecoder()
        consumer?.clear()
        val clock = clock ?: return
        if (!opened || closed || consumer == null) return
        lateinit var next: AssetAudioDecoder
        next =
            AssetAudioDecoder(
                context, checkNotNull(uri), clock, retiring,
                pcm = { buffer: ByteBuffer, format: PcmAudioFormat ->
                    synchronized(this) { if (decoder === next) consumer?.append(buffer, format) }
                },
                discontinuity = { synchronized(this) { if (decoder === next) consumer?.clear() } },
                error = { cause ->
                    val report = synchronized(this) { decoder === next && !closed }
                    if (report) onError(cause)
                },
            )
        retiring = null
        decoder = next
        next.start()
    }
}
