package com.haishinkit.rtmp.event

import org.junit.Assert.assertEquals
import org.junit.Test

class EventDispatcherMutationTest {
    @Test
    fun listenerAddedDuringCallbackStartsOnTheNextEvent() {
        val dispatcher = EventDispatcher(null)
        val calls = mutableListOf<String>()
        val late =
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    calls.add("late")
                }
            }
        var added = false
        dispatcher.addEventListener(
            "connect",
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    calls.add("initial")
                    if (!added) {
                        added = true
                        dispatcher.addEventListener("connect", late)
                    }
                }
            },
        )
        dispatcher.dispatchEventWith("connect")
        assertEquals(listOf("initial"), calls)
        dispatcher.dispatchEventWith("connect")
        assertEquals(listOf("initial", "initial", "late"), calls)
    }

    @Test
    fun listenerCanRemoveItselfWithoutInvalidatingDispatch() {
        val dispatcher = EventDispatcher(null)
        var calls = 0
        dispatcher.addEventListener(
            "connect",
            object : IEventListener {
                override fun handleEvent(event: Event) {
                    ++calls
                    dispatcher.removeEventListener("connect", this)
                }
            },
        )
        dispatcher.dispatchEventWith("connect")
        dispatcher.dispatchEventWith("connect")
        assertEquals(1, calls)
    }
}
