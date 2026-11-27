package com.haishinkit.app

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal object SampleVideo {
    /** Cache a complete copy off the UI thread; APK updates use a fresh file. */
    @Synchronized fun file(context: Context): File {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(context.cacheDir, "sample-video-$version.mp4")
        if (file.exists() && file.length() > 0) return file
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            context.resources.openRawResource(R.raw.sample_video).use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (cause: Throwable) {
            atomic.failWrite(output)
            throw cause
        }
        return file
    }
}
