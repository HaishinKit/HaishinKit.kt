package com.haishinkit.gles.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import com.haishinkit.gles.GraphicsContext
import com.haishinkit.media.source.VideoSource
import com.haishinkit.screen.Screen
import com.haishinkit.screen.ScreenObject
import com.haishinkit.screen.ScreenObjectContainer
import java.lang.ref.WeakReference

internal class ThreadScreen(
    applicationContext: Context,
) : Screen(applicationContext) {
    val graphicsContext: GraphicsContext
        get() {
            return screen.graphicsContext
        }

    override var id: Int
        get() = screen.id
        set(value) {
        }

    override var frame: Rect
        get() = screen.frame
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_BOUNDS, value))
            }
        }

    override var backgroundColor: Int
        get() = screen.backgroundColor
        set(value) {
            handler.apply {
                sendMessage(obtainMessage(MSG_SET_BACKGROUND_COLOR, value))
            }
        }

    private val screen: com.haishinkit.gles.screen.Screen by lazy {
        com.haishinkit.gles.screen.Screen(
            applicationContext,
        )
    }

    private val handler: Handler by lazy {
        val thread = HandlerThread(TAG)
        thread.start()
        Handler(screen, thread.looper)
    }

    init {
        screen.parent = this
        handler.apply {
            sendMessage(obtainMessage(MSG_START_RUNNING))
        }
    }

    override fun bringChildToFront(child: ScreenObject) {
        handler.apply {
            sendMessage(obtainMessage(MSG_BRING_CHILD_TO_FRONT, child))
        }
    }

    override fun sendChildToBack(child: ScreenObject) {
        handler.apply {
            sendMessage(obtainMessage(MSG_SEND_CHILD_TO_BACK, child))
        }
    }

    override fun addChild(child: ScreenObject?) {
        val child = child ?: return
        handler.apply {
            sendMessage(obtainMessage(MSG_ADD_CHILD, child))
        }
    }

    override fun bind(screenObject: ScreenObject) {
        handler.apply {
            sendMessage(obtainMessage(MSG_BIND, screenObject))
        }
    }

    override fun unbind(screenObject: ScreenObject) {
        handler.apply {
            sendMessage(obtainMessage(MSG_UNBIND, screenObject))
        }
    }

    override fun registerCallback(callback: Callback) {
        handler.apply {
            sendMessage(obtainMessage(MSG_REGISTER_CALLBACK, callback))
        }
    }

    override fun unregisterCallback(callback: Callback) {
        handler.apply {
            sendMessage(obtainMessage(MSG_UNREGISTER_CALLBACK, callback))
        }
    }

    override fun removeChild(child: ScreenObject?) {
        val child = child ?: return
        handler.apply {
            sendMessage(obtainMessage(MSG_REMOVE_CHILD, child))
        }
    }

    override fun readPixels(lambda: (bitmap: Bitmap?) -> Unit) {
        handler.apply {
            sendMessage(obtainMessage(MSG_READ_PIXELS, lambda))
        }
    }

    override fun transition(screenObjectContainer: ScreenObjectContainer) {
        handler.apply {
            sendMessage(obtainMessage(MSG_TRANSITION, screenObjectContainer))
        }
    }

    override fun attachVideo(
        track: Int,
        video: VideoSource?,
    ) {
        handler.apply {
            sendMessage(obtainMessage(MSG_ATTACH_VIDEO, track, 0, video))
        }
    }

    override fun getChildren(): List<ScreenObject> {
        return handler.run {
            screen.getChildren()
        }
    }

    override fun <T : ScreenObject> getScreenObjects(clazz: Class<T>): List<T> {
        return handler.run {
            screen.getScreenObjects(clazz)
        }
    }

    override fun dispose() {
        handler.apply {
            sendMessage(obtainMessage(MSG_DISPOSE))
        }
    }

    protected fun finalize() {
        handler.looper.quitSafely()
    }

    private class Handler(
        frame: com.haishinkit.gles.screen.Screen,
        looper: Looper,
    ) : android.os.Handler(looper) {
        private val transform = WeakReference(frame)

        override fun handleMessage(message: Message) {
            val transform = transform.get() ?: return
            when (message.what) {
                MSG_SET_BOUNDS -> {
                    transform.frame = message.obj as Rect
                }

                MSG_SET_BACKGROUND_COLOR -> {
                    transform.backgroundColor = message.obj as Int
                }

                MSG_START_RUNNING -> {
                    transform.startRunning()
                }

                MSG_ADD_CHILD -> {
                    transform.addChild(message.obj as ScreenObject)
                }

                MSG_REMOVE_CHILD -> {
                    transform.removeChild(message.obj as ScreenObject)
                }

                MSG_BRING_CHILD_TO_FRONT -> {
                    transform.bringChildToFront(message.obj as ScreenObject)
                }

                MSG_SEND_CHILD_TO_BACK -> {
                    transform.sendChildToBack(message.obj as ScreenObject)
                }

                MSG_REGISTER_CALLBACK -> {
                    transform.registerCallback(message.obj as Screen.Callback)
                }

                MSG_UNREGISTER_CALLBACK -> {
                    transform.unregisterCallback(message.obj as Screen.Callback)
                }

                MSG_BIND -> {
                    transform.bind(message.obj as ScreenObject)
                }

                MSG_UNBIND -> {
                    transform.unbind(message.obj as ScreenObject)
                }

                MSG_READ_PIXELS -> {
                    transform.readPixels(message.obj as (bitmap: Bitmap?) -> Unit)
                }

                MSG_TRANSITION -> {
                    transform.transition(message.obj as ScreenObjectContainer)
                }

                MSG_ATTACH_VIDEO -> {
                    transform.attachVideo(message.arg1, message.obj as? VideoSource)
                }

                MSG_DISPOSE -> {
                    transform.dispose()
                }

                else -> throw RuntimeException("Unhandled msg what=$message.what")
            }
        }
    }

    companion object {
        private val TAG = ThreadScreen::class.java.simpleName

        private const val MSG_SET_BOUNDS = 0
        private const val MSG_SET_BACKGROUND_COLOR = 1
        private const val MSG_START_RUNNING = 2
        private const val MSG_ADD_CHILD = 3
        private const val MSG_REMOVE_CHILD = 4
        private const val MSG_DISPOSE = 5
        private const val MSG_BRING_CHILD_TO_FRONT = 6
        private const val MSG_SEND_CHILD_TO_BACK = 7
        private const val MSG_REGISTER_CALLBACK = 8
        private const val MSG_UNREGISTER_CALLBACK = 9
        private const val MSG_BIND = 10
        private const val MSG_READ_PIXELS = 11
        private const val MSG_UNBIND = 12
        private const val MSG_TRANSITION = 13
        private const val MSG_ATTACH_VIDEO = 14
    }
}
