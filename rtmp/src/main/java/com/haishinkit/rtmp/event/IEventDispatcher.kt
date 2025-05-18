package com.haishinkit.rtmp.event

/**
 * The IEventDispatcher interface is in implementation which supports the DOM Event Model.
 */
interface IEventDispatcher {
    /**
     * Registers the event listeners on the event target.
     */
    fun addEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    )

    fun addEventListener(
        type: String,
        listener: IEventListener,
    ) {
        addEventListener(type, listener, false)
    }

    /**
     * Dispatches the events into the implementations event model.
     */
    fun dispatchEvent(event: Event)

    fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
        data: Any?,
    )

    fun dispatchEventWith(
        type: String,
        bubbles: Boolean,
    ) {
        dispatchEventWith(type, bubbles, null)
    }

    /**
     * Dispatches the events into the implementations event model.
     */
    fun dispatchEventWith(type: String) {
        dispatchEventWith(type, false, null)
    }

    /**
     * Unregister the event listeners on the event target.
     */
    fun removeEventListener(
        type: String,
        listener: IEventListener,
        useCapture: Boolean,
    )

    fun removeEventListener(
        type: String,
        listener: IEventListener,
    ) {
        removeEventListener(type, listener, false)
    }
}
