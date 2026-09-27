package com.haishinkit.lang

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle controls for a component that can be started and stopped.
 */
interface Running {
    /**
     * Indicates whether the receiver is running.
     */
    val isRunning: AtomicBoolean

    /**
     * Tells the receiver to start running.
     */
    fun startRunning()

    /**
     * Tells the receiver to stop running.
     */
    fun stopRunning()
}
