package com.haishinkit.media.audio

import android.os.Process
import android.util.Log
import com.haishinkit.media.AudioMixerSettings
import com.haishinkit.media.AudioMixerTrackSettings
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaType
import com.haishinkit.media.source.AudioSource
import com.haishinkit.media.source.PcmAudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/** A single output clock; blocking capture never runs on the mixing thread. */
internal class AudioMixer(private val owner: MediaMixer, private val output: (MediaBuffer) -> Unit) {
    @Volatile var settings = AudioMixerSettings()
    val hasAudio: Boolean get() = bindings.any { (it.source as? PushAudioSource)?.isClosed != true }

    @Volatile private var bindings = emptyArray<Binding>()

    @Volatile private var running = false
    private var disposed = false
    private var engine: NativeAudioMixer? = null
    private var renderThread: Thread? = null
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicInteger()
    private val defaultTrack = AudioMixerTrackSettings()

    suspend fun attach(
        track: Int,
        source: AudioSource?,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(!disposed) { "Audio mixer is disposed" }
                for (ended in bindings.filter { (it.source as? PushAudioSource)?.isClosed == true }) detach(ended)
                bindings.firstOrNull { it.track == track && it.source === source }?.let {
                    if (!it.opened) {
                        it.source.open(owner).getOrThrow()
                        it.opened = true
                    }
                    return@withLock Result.success(Unit)
                }
                bindings.firstOrNull { it.track == track }?.let { detach(it) }
                if (source == null) return@withLock Result.success(Unit)
                require(bindings.size < 32) { "At most 32 audio tracks can be mixed" }
                val opened =
                    try {
                        source.open(owner)
                    } catch (cause: Exception) {
                        withContext(NonCancellable) { runCatching { source.close() } }
                        if (cause is CancellationException) throw cause
                        return@withLock Result.failure(cause)
                    }
                if (opened.isFailure) {
                    withContext(NonCancellable) { source.close() }
                    (opened.exceptionOrNull() as? CancellationException)?.let { throw it }
                    return@withLock opened
                }
                val binding = Binding(track, ids.incrementAndGet(), source)
                bindings = bindings + binding
                (source as? PushAudioSource)?.onEnded = {
                    scope.launch { lock.withLock { if (bindings.any { it === binding }) detach(binding) } }
                }
                try {
                    if (running) {
                        val existing = engine
                        if (existing == null) startEngine() else startBinding(binding, existing)
                    }
                    Result.success(Unit)
                } catch (cause: Exception) {
                    detach(binding)
                    Result.failure(cause)
                }
            }
        }

    suspend fun start() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (running || disposed || !owner.isRunning.get()) return@withLock
                running = true
                for (binding in bindings) if (!binding.opened) {
                    binding.source.open(owner).getOrThrow()
                    binding.opened = true
                }
                if (bindings.isNotEmpty()) startEngine()
            }
        }

    suspend fun stop(dispose: Boolean = false) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!dispose && owner.isRunning.get()) return@withLock
                running = false
                renderThread?.interrupt()
                renderThread?.join()
                renderThread = null
                for (binding in bindings) stopBinding(binding)
                engine?.close()
                engine = null
                if (dispose) {
                    disposed = true
                    for (binding in bindings) (binding.source as? PushAudioSource)?.onEnded = null
                    bindings = emptyArray()
                    scope.cancel()
                }
            }
        }

    private suspend fun detach(binding: Binding) {
        bindings = bindings.filterNot { it === binding }.toTypedArray()
        (binding.source as? PushAudioSource)?.onEnded = null
        stopBinding(binding)
        engine?.remove(binding.nativeId)
    }

    private suspend fun stopBinding(binding: Binding) {
        binding.capturing = false
        (binding.source as? PushAudioSource)?.consumer = null
        if (binding.opened) binding.source.close().onFailure { Log.w(TAG, "Unable to close audio track ${binding.track}", it) }
        binding.opened = false
        binding.worker?.interrupt()
        binding.worker?.join()
        binding.worker = null
    }

    private fun startEngine() {
        val next = NativeAudioMixer(settings)
        engine = next
        for (binding in bindings) startBinding(binding, next)
        renderThread =
            Thread({ render(next) }, "HaishinKit.AudioMix").apply {
                isDaemon = true
                start()
            }
    }

    private fun startBinding(
        binding: Binding,
        mixer: NativeAudioMixer,
    ) {
        mixer.add(binding.nativeId)
        binding.capturing = true
        binding.gain = Float.NaN
        configure(binding, mixer)
        val pushed = binding.source as? PushAudioSource
        if (pushed != null) {
            pushed.consumer =
                object : PcmAudioConsumer {
                    override fun append(
                        buffer: ByteBuffer,
                        format: PcmAudioFormat,
                    ) {
                        if (binding.capturing && buffer.hasRemaining()) mixer.append(binding.nativeId, buffer, format)
                    }

                    override fun clear() = mixer.clear(binding.nativeId)
                }
        } else {
            val format = (binding.source as? PcmAudioSource)?.audioFormat ?: PcmAudioFormat(settings.sampleRate, settings.channels)
            binding.worker =
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    try {
                        while (binding.capturing) {
                            val pcm = binding.source.read(binding.track).payload
                            if (binding.capturing && pcm != null && pcm.hasRemaining()) {
                                mixer.append(binding.nativeId, pcm, format)
                            } else {
                                LockSupport.parkNanos(10_000_000)
                            }
                        }
                    } catch (cause: Exception) {
                        if (binding.capturing) Log.w(TAG, "Audio input stopped on track ${binding.track}", cause)
                    }
                }, "HaishinKit.AudioInput.${binding.track}").apply {
                    isDaemon = true
                    start()
                }
        }
    }

    private fun configure(
        binding: Binding,
        mixer: NativeAudioMixer,
    ) {
        val value = settings.tracks[binding.track] ?: defaultTrack
        val muted = value.isMuted || binding.source.isMuted
        if (value.volume != binding.gain || muted != binding.muted) {
            mixer.configure(binding.nativeId, if (muted == value.isMuted) value else value.copy(isMuted = muted))
            binding.gain = value.volume
            binding.muted = muted
        }
    }

    private fun render(mixer: NativeAudioMixer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val rate = settings.sampleRate
        val anchor = System.nanoTime()
        var frames = 0L
        val buffer = MediaBuffer(MediaType.AUDIO, 0, mixer.output)
        try {
            while (running) {
                val deadline = anchor + frames * 1_000_000L / rate * 1000
                while (running) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    LockSupport.parkNanos(remaining)
                }
                if (!running) break
                for (binding in bindings) configure(binding, mixer)
                mixer.render(settings.isMuted)
                buffer.timestamp = anchor / 1000 + frames * 1_000_000L / rate
                output(buffer)
                frames += mixer.frames
            }
        } catch (cause: Exception) {
            Log.e(TAG, "Audio mixing stopped", cause)
        }
    }

    private class Binding(val track: Int, val nativeId: Int, val source: AudioSource) {
        @Volatile var capturing = false
        var opened = true
        var worker: Thread? = null
        var gain = Float.NaN
        var muted = false
    }

    companion object {
        private const val TAG = "AudioMixer"
    }
}
