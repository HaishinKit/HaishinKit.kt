package com.haishinkit.util

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The util object to get feature flag info.
 */
object FeatureUtil {
    const val FEATURE_BITRATE_CHANGE = "bitrate_change"

    private var flags =
        ConcurrentHashMap(
            mutableMapOf(
                FEATURE_BITRATE_CHANGE to true,
            ),
        )

    /**
     * Whether or not a flag is enabled.
     */
    fun isEnabled(feature: String): Boolean = flags[feature] ?: false

    /**
     * Setter for a feature flag.
     */
    fun setEnabled(
        feature: String,
        isEnabled: Boolean,
    ) {
        flags[feature] = isEnabled
    }

    override fun toString(): String = JSONObject(flags.toMap()).toString()
}
