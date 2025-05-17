package com.haishinkit.media

import com.haishinkit.screen.Screen

interface MediaOutput {
    var screen: Screen?

    fun append(buffer: MediaBuffer)
}
