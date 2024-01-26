package com.haishinkit.screen

/**
 *  A ScreenObjectContainer represents a collection of screen objects.
 */
open class ScreenObjectContainer : ScreenObject() {
    val childCounts: Int
        get() = children.size

    override var parent: ScreenObjectContainer? = null
        set(value) {
            children.forEach {
                (root as? Screen)?.unbind(it)
            }
            field = value
            children.forEach {
                (root as? Screen)?.bind(it)
            }
        }

    private val children = mutableListOf<ScreenObject>()

    open fun bringChildToFront(child: ScreenObject) {
        val index = children.indexOf(child)
        if (0 < index) {
            children.removeAt(index)
            children.add(0, child)
            invalidateLayout()
        }
    }

    open fun sendChildToBack(child: ScreenObject) {
        val index = children.indexOf(child)
        if (0 < index) {
            children.removeAt(index)
            children.add(child)
            invalidateLayout()
        }
    }

    /**
     * Adds the specified screen object as a child of the current screen object container.
     */
    open fun addChild(child: ScreenObject) {
        if (child.parent != null || child == this) {
            throw IllegalArgumentException()
        }
        children.add(child)
        child.parent = this
    }

    /**
     * Removes the specified screen object as a child of the current screen object container.
     */
    open fun removeChild(child: ScreenObject) {
        if (child.parent != this) {
            return
        }
        children.remove(child)
        child.parent = null
    }

    override fun layout(renderer: ScreenRenderer) {
        children.forEach {
            if (it.shouldInvalidateLayout || renderer.shouldInvalidateLayout) {
                it.layout(renderer)
            }
        }
    }

    override fun draw(renderer: ScreenRenderer) {
        children.forEach {
            if (it.isVisible) {
                it.draw(renderer)
            }
        }
    }

    open fun dispose() {
        for (i in children.size - 1 downTo 0) {
            removeChild(children[i])
        }
        children.clear()
    }
}
