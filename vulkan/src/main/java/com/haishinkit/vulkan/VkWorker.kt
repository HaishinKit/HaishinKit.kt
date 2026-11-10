package com.haishinkit.vulkan

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.FutureTask

internal object VkWorker {
    val handler by lazy { Handler(thread.looper) }
    private val thread = HandlerThread("VkScreen").apply { start() }

    fun <T> run(action: () -> T): T {
        if (Looper.myLooper() == thread.looper) return action()
        val task = FutureTask(action)
        check(handler.post(task))
        try {
            return task.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }
}
