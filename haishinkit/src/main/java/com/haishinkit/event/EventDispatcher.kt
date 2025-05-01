package com.haishinkit.event

import androidx.core.util.Pools
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
                for (listener in listeners) {
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
        val event = pool.acquire() ?: Event(type, bubbles, data)
        event.type = type
        event.isBubbles = bubbles
        event.data = data
        dispatchEvent(event)
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
        var i = list.size - 1
        while (0 <= i) {
            if (list[i] === listener) {
                list.removeAt(i)
                return
            }
            --i
        }
    }

    companion object {
        const val MAX_POOL_SIZE = 16
    }
}
