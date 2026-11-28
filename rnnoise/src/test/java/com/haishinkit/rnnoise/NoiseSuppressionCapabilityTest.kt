package com.haishinkit.rnnoise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NoiseSuppressionCapabilityTest {
    @Test fun enumerationFailureIsNotTreatedAsMissingSuppression() {
        assertThrows(IllegalStateException::class.java) { requireNoiseSuppressionCapability(null) }
    }

    @Test fun successfulEmptyEnumerationMeansUnavailable() {
        assertFalse(requireNoiseSuppressionCapability(emptyArray()))
    }
}
