package com.haishinkit.rtmp.event

/**
 * Carries an event name, payload, and dispatch metadata.
 *
 * @param type The event name.
 * @param bubbles Whether the event is marked as bubbling.
 * @param data The event payload, or `null`.
 */
open class Event(
    type: String,
    bubbles: Boolean,
    data: Any?,
) {
    /**
     * The event name, such as [RTMP_STATUS] or [IO_ERROR].
     */
    var type: String? = null
        internal set

    /**
     * The originating dispatcher, when assigned.
     */
    var target: IEventDispatcher? = null
        internal set

    /**
     * The dispatcher currently delivering the event.
     */
    var currentTarget: IEventDispatcher? = null
        internal set

    /**
     * The event payload, or `null` if no payload is supplied.
     */
    var data: Any? = null
        internal set

    /**
     * Whether this event is marked as bubbling.
     */
    var isBubbles = false
        internal set

    internal var propagationStopped = false
    internal var eventPhase = EVENT_PHASE_NONE

    init {
        this.type = type
        this.data = data
        this.isBubbles = bubbles
    }

    /**
     * Marks propagation as stopped after the current target's listeners finish.
     */
    fun stopPropagation() {
        propagationStopped = true
    }

    companion object {
        const val RTMP_STATUS = "rtmpStatus"
        const val IO_ERROR = "ioError"

        const val EVENT_PHASE_NONE = 0x00
        const val EVENT_PHASE_CAPTURING = 0x01
        const val EVENT_PHASE_AT_TARGET = 0x02
        const val EVENT_PHASE_BUBBLING = 0x03
    }
}
