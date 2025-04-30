package com.haishinkit.media.source

import com.haishinkit.lang.Running
import com.haishinkit.media.MediaMixer

/**
 * An interface that captures a source.
 */
interface Source : Running {
    var mixer: MediaMixer?
}
