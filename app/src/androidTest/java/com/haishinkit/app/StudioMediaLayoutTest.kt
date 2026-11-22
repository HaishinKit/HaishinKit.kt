package com.haishinkit.app

import android.Manifest
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudioMediaLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun wideWindowKeepsToolsBesideAnUnobstructedInteractivePreview() {
        var previewTaps = 0
        compose.setContent {
            LayoutFixture {
                Box(Modifier.requiredSize(1120.dp, 640.dp)) {
                    StudioMediaLayout(
                        preview = { expanded ->
                            StudioPreviewFrame(expanded, 16f / 9f) {
                                Box(Modifier.fillMaxSize().testTag("preview-touch").clickable { previewTaps++ })
                            }
                        },
                        controls = { expanded -> TestControls(expanded) },
                    )
                }
            }
        }
        val preview = compose.onNodeWithTag("studio-preview").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("studio-control-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.left >= preview.right)
        assertTrue(preview.width > panel.width)
        val frame = compose.onNodeWithTag("studio-video-frame").fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, frame.width / frame.height, 0.01f)
        assertTrue(frame.top >= preview.top && frame.bottom <= preview.bottom)
        compose.onNodeWithText("Camera selector").assertIsDisplayed()
        compose.onNodeWithTag("preview-touch").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, previewTaps) }
        saveScreenshot("tablet-landscape")
    }

    @Test
    fun narrowWindowRetainsCompactControlsAndHiddenAdjustments() {
        showLayout(390.dp, 640.dp)
        compose.onNodeWithTag("studio-control-panel").assertDoesNotExist()
        compose.onNodeWithText("Camera selector").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.studio_go_live)).assertIsDisplayed()
        saveScreenshot("phone-portrait")
    }

    @Test
    fun shortLandscapeWindowDoesNotSqueezeInASidePanel() {
        showLayout(960.dp, 360.dp)
        compose.onNodeWithTag("studio-control-panel").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.studio_go_live)).assertIsDisplayed()
        saveScreenshot("phone-landscape")
    }

    @Test
    fun resizingAcrossTheBreakpointDoesNotRecreateTheNativePreview() {
        val width = mutableStateOf(1120.dp)
        var previewsCreated = 0
        compose.setContent {
            LayoutFixture {
                Box(Modifier.requiredSize(width.value, 600.dp)) {
                    StudioMediaLayout(
                        preview = { expanded ->
                            StudioPreviewFrame(expanded, 16f / 9f) {
                                AndroidView(factory = {
                                    previewsCreated++
                                    View(it)
                                }, modifier = Modifier.fillMaxSize())
                            }
                        },
                        controls = { expanded -> TestControls(expanded) },
                    )
                }
            }
        }
        compose.onNodeWithTag("studio-control-panel").assertIsDisplayed()
        compose.runOnIdle { width.value = 600.dp }
        compose.onNodeWithTag("studio-control-panel").assertDoesNotExist()
        compose.runOnIdle { width.value = 1120.dp }
        compose.onNodeWithTag("studio-control-panel").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, previewsCreated) }
    }

    @Test
    fun longSettingsDoNotHideThePrimaryActions() {
        showLayout(1000.dp, 600.dp)
        compose.onNodeWithText("Last camera setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.studio_go_live)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.studio_snapshot)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.studio_record)).assertIsDisplayed()
        saveScreenshot("tablet-scrolled-tools")
    }

    @Test
    fun pictureInPictureShowsOnlyThePreviewEvenInAWideWindow() {
        showLayout(1120.dp, 640.dp, pictureInPicture = true)
        compose.onNodeWithTag("studio-control-panel").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.studio_go_live)).assertDoesNotExist()
        compose.onNodeWithTag("studio-preview").assertIsDisplayed()
    }

    @OptIn(ExperimentalPermissionsApi::class)
    @Test
    fun missingPermissionsShowRecoveryInstructionsOnlyOnceInSidePanel() {
        compose.setContent {
            LayoutFixture {
                Box(Modifier.requiredSize(1120.dp, 640.dp)) {
                    PublishControls(
                        expanded = true,
                        needsPermissions = true,
                        settings = {
                            CameraDeviceControllerView(
                                camera = null,
                                cameras = emptyList(),
                                onCameraSelected = {},
                                permissions =
                                    CameraPermissions(
                                        audio = DeniedPermission(Manifest.permission.RECORD_AUDIO),
                                        camera = DeniedPermission(Manifest.permission.CAMERA),
                                    ),
                            )
                        },
                        actions = {},
                    )
                }
            }
        }
        compose.onAllNodesWithText(label(R.string.studio_permission_help)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.studio_app_permissions)).assertIsDisplayed()
    }

    private fun showLayout(
        width: Dp,
        height: Dp,
        pictureInPicture: Boolean = false,
    ) {
        compose.setContent {
            LayoutFixture {
                Box(Modifier.requiredSize(width, height)) {
                    StudioMediaLayout(
                        pictureInPicture = pictureInPicture,
                        preview = { Box(Modifier.fillMaxSize().background(Color(0xFF223D50))) },
                        controls = { expanded -> TestControls(expanded) },
                    )
                }
            }
        }
    }

    private fun label(resource: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(resource)

    private fun saveScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("layoutScreenshots") != "true") return
        val directory = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "layout-previews")
        val output = File(directory, "$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onNodeWithTag("studio-media-layout").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@Suppress("ktlint:standard:function-naming")
@androidx.compose.runtime.Composable
private fun TestControls(expanded: Boolean) {
    PublishControls(
        modifier = Modifier.fillMaxSize(),
        expanded = expanded,
        onOpenIngest = {},
        settings = {
            Text("Camera selector")
            Box(Modifier.height(800.dp))
            Text("Last camera setting")
        },
        actions = { CameraControllerView(false, false, {}, {}, {}) },
    )
}

/** Fit logical test windows on phones too, without changing system display settings. */
@Suppress("ktlint:standard:function-naming")
@androidx.compose.runtime.Composable
private fun LayoutFixture(
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = with(density) { minOf(1f, maxWidth.toPx() / 1120f, maxHeight.toPx() / 640f) }
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            StudioTheme(content)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
private class DeniedPermission(override val permission: String) : PermissionState {
    override val status: PermissionStatus = PermissionStatus.Denied(shouldShowRationale = false)

    override fun launchPermissionRequest() = Unit
}
