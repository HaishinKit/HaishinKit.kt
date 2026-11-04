package com.haishinkit.app

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MediaProjectionBindingTest {
    @Test
    fun stopWaitsForCleanupReplyThenUnbindsBeforeAnotherStart() {
        var closeReply: Messenger? = null
        val remote =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.what == MediaProjectionService.MSG_CLOSE) closeReply = message.replyTo
                    true
                },
            )
        val context = FakeContext(remote)
        val binding = MediaProjectionBinding(context)
        val intent = Intent(context, MediaProjectionService::class.java)
        binding.bind(intent)
        binding.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(binding.isStopping)
        assertNotNull(closeReply)
        assertEquals(0, context.unbinds)
        closeReply!!.send(Message.obtain(null, MediaProjectionService.MSG_CLOSED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, context.unbinds)
        assertFalse(binding.isStarted)
        assertFalse(binding.isStopping)
        binding.bind(intent)
        assertEquals(2, context.binds)
        assertTrue(binding.isStarted)
        binding.dispose()
    }

    private class FakeContext(private val remote: Messenger) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var binds = 0
        var unbinds = 0

        override fun bindService(
            intent: Intent,
            connection: ServiceConnection,
            flags: Int,
        ): Boolean {
            ++binds
            connection.onServiceConnected(ComponentName(this, MediaProjectionService::class.java), remote.binder)
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            ++unbinds
        }
    }
}
