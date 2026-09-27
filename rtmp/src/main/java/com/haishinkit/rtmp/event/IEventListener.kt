package com.haishinkit.rtmp.event

/**
 * Handles events delivered by an [IEventDispatcher].
 */
interface IEventListener {
    /**
     * Handles an event synchronously during dispatch.
     *
     * Events may be pooled and reused. Copy any data needed after this callback returns.
     */
    fun handleEvent(event: Event)
}
