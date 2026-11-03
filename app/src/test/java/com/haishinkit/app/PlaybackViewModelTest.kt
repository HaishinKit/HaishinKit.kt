package com.haishinkit.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PlaybackViewModelTest {
    @Test
    fun invalidInitialSettingsAreReportedWithoutConstructingOrDisposingAStream() =
        runBlocking {
            val previous = Preference.shared
            try {
                for (url in listOf("", "https://example.com/live")) {
                    Preference.shared = Preference(url, "test")
                    val model = PlaybackViewModel(RuntimeEnvironment.getApplication())
                    assertTrue(model.refreshSession().isFailure)
                    assertNull(model.session)
                    assertNotNull(model.sessionError)
                }
            } finally {
                Preference.shared = previous
            }
        }
}
