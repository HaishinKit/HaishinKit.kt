package com.haishinkit.rtmp.event

import androidx.core.util.Pools
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatches events to listeners registered by event name and capture flag.
 *
 * @param target The dispatcher exposed as the current event target, or `null` to use this instance.
 */
open class EventDispatcher(
    private val target: IEventDispatcher?,
) : IEventDispatcher {
    private val pool = Pools.SynchronizedPool<Event>(MAX_POOL_SIZE)
    private val listeners = ConcurrentHashMap<String, MutableList<IEventListener>>()

    override fun addEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        val key = "$type/$useCapture"
        listeners.putIfAbsent(key, Collections.synchronizedList(mutableListOf<IEventListener>()))
        listeners[key]?.add(listener)
    }

    override fun dispatchEvent(event: Event) {
        dispatchEvent(event) { true }
    }

    private fun dispatchEvent(
        event: Event,
        isCurrent: () -> Boolean,
    ) {
        if (event.type == null) {
            throw IllegalArgumentException()
        }

        val targets = ArrayList<IEventDispatcher>()
        targets.add(target ?: this)

        for (target in targets) {
            event.currentTarget = target
            val isTargetPhase = target === event.target

            if (isTargetPhase) {
                event.eventPhase = Event.EVENT_PHASE_AT_TARGET
            }

            val isCapturingPhase = (event.eventPhase == Event.EVENT_PHASE_CAPTURING).toString()
            val listeners = this.listeners["${event.type}/$isCapturingPhase"]
            if (listeners != null) {
                val snapshot = synchronized(listeners) { listeners.toList() }
                for (listener in snapshot) {
                    if (!isCurrent()) break
                    listener.handleEvent(event)
                }
                if (event.propagationStopped) {
                    break
                }
            }

            if (isTargetPhase) {
                event.eventPhase = Event.EVENT_PHASE_BUBBLING
            }
        }

        event.target = null
        event.currentTarget = null
        event.eventPhase = Event.EVENT_PHASE_NONE
        event.propagationStopped = false
    }

    override fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
    ) {
        dispatchPooledEvent(type, bubbles, data, ::dispatchEvent)
    }

    internal fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
        isCurrent: () -> Boolean,
    ) {
        dispatchPooledEvent(type, bubbles, data) { dispatchEvent(it, isCurrent) }
    }

    private fun dispatchPooledEvent(
        type: String,
        bubbles: Boolean,
        data: Any?,
        dispatch: (Event) -> Unit,
    ) {
        val event = pool.acquire() ?: Event(type, bubbles, data)
        event.type = type
        event.isBubbles = bubbles
        event.data = data
        dispatch(event)
        pool.release(event)
    }

    override fun removeEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    ) {
        val key = "$type/$useCapture"
        if (!listeners.containsKey(key)) {
            return
        }
        val list = listeners[key]!!
        synchronized(list) {
            val index = list.indexOfLast { it === listener }
            if (index >= 0) list.removeAt(index)
        }
    }

    companion object {
        const val MAX_POOL_SIZE = 16
    }
}
