package com.haishinkit.compose

/**
 * The Android view implementation used by [HaishinKitView].
 */
enum class HaishinKitViewType {
    /**
     * Uses a `HkSurfaceView` backed by an Android `SurfaceView`.
     */
    SurfaceView,

    /**
     * Uses a `HkTextureView` backed by an Android `TextureView`.
     */
    TextureView,
}
