package com.haishinkit.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SceneFileControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingToolsAfterPickingAFileDoesNotCancelItsRead() {
        val visible = mutableStateOf(true)
        val release = CompletableDeferred<Unit>()
        var started = false
        var completed = false
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry =
                    object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<I, O>,
                            input: I,
                            options: ActivityOptionsCompat?,
                        ) {
                            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://scene/test")))
                        }
                    }
            }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val actions =
                    rememberSceneFileActions(
                        onSave = { Result.success(Unit) },
                        onOpen = {
                            started = true
                            release.await()
                            completed = true
                            Result.success(Unit)
                        },
                        onError = { error(it) },
                    )
                if (visible.value) Column { SceneFileControls(true, false, actions) }
            }
        }
        compose.onNodeWithTag("scene-open").performClick()
        compose.waitUntil { started }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil { completed }
        assertTrue(completed)
    }
}
