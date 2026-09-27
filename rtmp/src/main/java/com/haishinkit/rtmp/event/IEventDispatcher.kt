package com.haishinkit.rtmp.event

/**
 * Registers listeners and dispatches events.
 */
interface IEventDispatcher {
    /**
     * Registers a listener for an event name and capture flag.
     *
     * @param type The event name.
     * @param listener The listener to register.
     * @param useCapture Whether to register for the capture phase.
     */
    fun addEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    )

    /**
     * Registers a listener with capture disabled.
     */
    fun addEventListener(
        type: String,
        listener: IEventListener,
    ) {
        addEventListener(type, listener, false)
    }

    /**
     * Delivers an event to the matching listeners.
     */
    fun dispatchEvent(event: Event)

    /**
     * Creates and dispatches an event with the given name, bubbling flag, and payload.
     */
    fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
    )

    /**
     * Creates and dispatches an event without a payload.
     */
    fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
    ) {
        dispatchEventWith(type, bubbles, null)
    }

    /**
     * Creates and dispatches a non-bubbling event without a payload.
     */
    fun dispatchEventWith(type: String) {
        dispatchEventWith(type, false, null)
    }

    /**
     * Unregisters a listener using the event name and capture flag supplied during registration.
     */
    fun removeEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    )

    /**
     * Unregisters a listener registered with capture disabled.
     */
    fun removeEventListener(
        type: String,
        listener: IEventListener,
    ) {
        removeEventListener(type, listener, false)
    }
}
