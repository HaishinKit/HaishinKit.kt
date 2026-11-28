package com.haishinkit.rnnoise

import android.media.audiofx.AudioEffect

internal fun requireNoiseSuppressionCapability(descriptors: Array<AudioEffect.Descriptor>?): Boolean =
    checkNotNull(descriptors) { "Cannot enumerate Android audio effects" }.any { it.type == AudioEffect.EFFECT_TYPE_NS }
