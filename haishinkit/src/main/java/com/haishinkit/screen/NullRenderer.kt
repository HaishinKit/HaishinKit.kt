package com.haishinkit.screen

internal class NullRenderer : Renderer {
    override fun layout(screenObject: ScreenObject) {
    }

    override fun draw(screenObject: ScreenObject) {
    }

    companion object {
        internal val SHARED = NullRenderer()
    }
}
