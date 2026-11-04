package com.haishinkit.media

import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** Registration is serialized; delivery iterates a stable snapshot without holding the lock. */
internal class MediaOutputRegistry(private val source: MediaOutputDataSource) {
    private val outputs = CopyOnWriteArrayList<MediaOutput>()

    fun register(output: MediaOutput) {
        synchronized(outputs) {
            if (outputs.contains(output)) return
            output.dataSource = WeakReference(source)
            outputs.add(output)
        }
    }

    fun unregister(output: MediaOutput) {
        synchronized(outputs) {
            if (outputs.remove(output)) output.dataSource = null
        }
    }

    fun forEach(action: (MediaOutput) -> Unit) {
        for (output in outputs) action(output)
    }

    fun clear() {
        for (output in outputs) unregister(output)
    }
}
