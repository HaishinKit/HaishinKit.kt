package com.haishinkit.rtmp.event

/**
 * The Event interface is used to provide information.
 */
open class Event(
    type: String,
    bubbles: Boolean,
    data: Any?,
) {
    /**
     * The type represents the event name.
     */
    var type: String? = null
        internal set

    /**
     * The target indicates the [IEventDispatcher].
     */
    var target: IEventDispatcher? = null
        internal set

    /**
     * The currentTarget indicates the [IEventDispatcher]s are currently being evaluated.
     */
    var currentTarget: IEventDispatcher? = null
        internal set

    /**
     * The data indicates the to provide information.
     */
    var data: Any? = null
        internal set

    /**
     * The isBubbles indicates whether ot not an event is a bubbling event.
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
