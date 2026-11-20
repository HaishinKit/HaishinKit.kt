package com.haishinkit.app

import com.haishinkit.screen.Screen

/** Release builds never register a composition for remote capture. */
internal object DebugFrameCapture {
    fun attach(
        owner: Any,
        screen: Screen,
    ) = Unit

    fun detach(owner: Any) = Unit
}
