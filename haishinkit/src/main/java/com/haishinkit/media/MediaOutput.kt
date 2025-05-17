package com.haishinkit.media

import com.haishinkit.screen.Screen
import java.lang.ref.WeakReference

interface MediaOutput {
    var dataSource: WeakReference<MediaOutputDataSource>?

    var screen: Screen?

    fun append(buffer: MediaBuffer)
}
