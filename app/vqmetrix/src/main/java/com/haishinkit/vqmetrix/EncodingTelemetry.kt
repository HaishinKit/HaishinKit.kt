package com.haishinkit.vqmetrix

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** App-wide resource samples around offline replay, not hardware encoder utilization. */
internal class EncodingTelemetry(context: Context, private val frames: Int, private val fps: Int) {
    private val started = SystemClock.elapsedRealtimeNanos()
    private val cpuStarted = Process.getElapsedCpuTime()
    private val power = context.getSystemService(PowerManager::class.java)
    private val pending = mutableMapOf<Long, Double>()
    private val timings = mutableListOf<JSONObject>()
    private val latencies = mutableListOf<Double>()
    private val samples = mutableListOf<JSONObject>()
    private var lastSample = -250.0
    private var peakMemory = 0L
    private var firstOutput: Double? = null

    init {
        sample(force = true)
    }

    fun input(ptsUs: Long) {
        sample()
        pending[ptsUs] = elapsed()
    }

    fun output(ptsUs: Long) {
        val now = elapsed()
        if (firstOutput == null) firstOutput = now
        pending.remove(ptsUs)?.let { input ->
            val latency = (now - input).coerceAtLeast(0.0)
            latencies.add(latency)
            timings.add(
                JSONObject().apply {
                    put("ptsUs", ptsUs)
                    put("inputElapsedMs", input)
                    put("outputElapsedMs", now)
                    put("latencyMs", latency)
                },
            )
        }
        sample()
    }

    fun finish(): JSONObject {
        sample(force = true)
        val wall = elapsed().coerceAtLeast(0.001)
        val cpu = (Process.getElapsedCpuTime() - cpuStarted).coerceAtLeast(0).toDouble()
        val sorted = latencies.sorted()
        val p95 = sorted.getOrNull((ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0))
        val duration = frames * 1000.0 / fps
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("platform", "android")
            put("mode", "offlineReplay")
            put("wallTimeMs", wall)
            put("firstOutputMs", firstOutput ?: JSONObject.NULL)
            put("frameCount", timings.size)
            put("throughputFps", timings.size * 1000.0 / wall)
            put("videoDurationMs", duration)
            put("realtimeSpeed", duration / wall)
            put("meanLatencyMs", if (latencies.isEmpty()) JSONObject.NULL else latencies.average())
            put("p95LatencyMs", p95 ?: JSONObject.NULL)
            put("appCpuTimeMs", cpu)
            put("appCpuCores", cpu / wall)
            put("memoryKind", "pss")
            put("sampledPeakMemoryBytes", peakMemory)
            put("sampleIntervalMs", 250)
            put("samples", JSONArray(samples))
            put("frameTimings", JSONArray(timings))
        }
    }

    private fun elapsed(): Double = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun sample(force: Boolean = false) {
        val time = elapsed()
        if (!force && time - lastSample < 250) return
        lastSample = time
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val bytes = memory.totalPss.toLong() * 1024
        peakMemory = maxOf(peakMemory, bytes)
        val thermal = power.currentThermalStatus
        samples.add(
            JSONObject().apply {
                put("elapsedMs", time)
                put("appCpuTimeMs", (Process.getElapsedCpuTime() - cpuStarted).coerceAtLeast(0))
                put("memoryBytes", bytes)
                put(
                    "thermalState",
                    when (thermal) {
                        PowerManager.THERMAL_STATUS_NONE -> "nominal"
                        PowerManager.THERMAL_STATUS_LIGHT, PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                        PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                        else -> "critical"
                    },
                )
                put("nativeThermalStatus", thermal)
            },
        )
    }
}
