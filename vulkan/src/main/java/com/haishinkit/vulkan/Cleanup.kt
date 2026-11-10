package com.haishinkit.vulkan

/** Attempts every resource release, then propagates the first failure with later ones suppressed. */
internal fun cleanupAll(actions: Iterable<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else if (error !== failure) {
                failure!!.addSuppressed(error)
            }
        }
    }
    failure?.let { throw it }
}
