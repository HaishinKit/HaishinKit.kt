package com.haishinkit.screen

/**
 * Lays out and draws screen objects using a rendering backend.
 */
interface Renderer {
    /**
     * Updates rendering resources and geometry for the object.
     */
    fun layout(screenObject: ScreenObject)

    /**
     * Draws the object using its current layout and rendering state.
     */
    fun draw(screenObject: ScreenObject)
}
