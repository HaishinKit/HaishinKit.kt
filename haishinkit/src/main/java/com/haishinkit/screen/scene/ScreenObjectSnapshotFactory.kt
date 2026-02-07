package com.haishinkit.screen.scene

import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer

class ScreenObjectSnapshotFactory {
    fun create(screenObject: ScreenObject): ScreenObjectSnapshot {
        return when (screenObject) {
            is ScreenObjectContainer -> {
                ScreenObjectSnapshot(
                    type = screenObject.type,
                    id = screenObject.id,
                    frame =
                        ScreenObjectSnapshot.Rect(
                            screenObject.frame.top,
                            screenObject.frame.left,
                            screenObject.frame.width(),
                            screenObject.frame.height(),
                        ),
                    isVisible = screenObject.isVisible,
                    horizontalAlignment = screenObject.horizontalAlignment,
                    verticalAlignment = screenObject.horizontalAlignment,
                    layoutMargin = screenObject.layoutMargin,
                    elements = screenObject.elements,
                    children =
                        screenObject.getChildren().map {
                            create(it)
                        },
                )
            }

            else -> {
                ScreenObjectSnapshot(
                    type = screenObject.type,
                    id = screenObject.id,
                    frame =
                        ScreenObjectSnapshot.Rect(
                            screenObject.frame.top,
                            screenObject.frame.left,
                            screenObject.frame.width(),
                            screenObject.frame.height(),
                        ),
                    isVisible = screenObject.isVisible,
                    horizontalAlignment = screenObject.horizontalAlignment,
                    verticalAlignment = screenObject.verticalAlignment,
                    layoutMargin = screenObject.layoutMargin,
                    elements = screenObject.elements,
                    children = emptyList(),
                )
            }
        }
    }
}
