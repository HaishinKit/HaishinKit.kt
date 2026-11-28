package com.haishinkit.app.chatgpt

import com.haishinkit.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AiChatTurnStateTest {
    @Test
    fun failuresAndStopsNeverCommitQuestionsOrDisableTheFirstModelPicker() {
        var state = AiChatState(model = "model")
        repeat(20) {
            state = state.beginTurn("Uncompleted $it", it).copy(imageApproved = true)
            assertTrue(state.lines.isEmpty())
            assertEquals("Uncompleted $it", state.pendingPrompt)
            state = state.discardPendingTurn(hasImages = false).copy(busy = false)
            assertTrue(state.lines.isEmpty())
            assertNull(state.pendingPrompt)
            assertFalse(state.imageApproved)
            assertTrue(state.canChangeModel)
        }
    }

    @Test
    fun onlyCompletedPairsRemainInTheBoundedTranscript() {
        var state = AiChatState()
        repeat(10) { turn ->
            state = state.beginTurn("Q$turn", turn).completeTurn("Q$turn", "A$turn", hasImages = turn < 6)
        }
        assertEquals(12, state.lines.size)
        assertEquals(ChatLine(true, "Q4"), state.lines.first())
        assertEquals(ChatLine(false, "A9"), state.lines.last())
        repeat(10) { state = state.beginTurn("Failed", 100 + it).discardPendingTurn(false).copy(busy = false) }
        assertEquals(12, state.lines.size)
        assertFalse(state.imageApproved)
    }

    @Test
    fun ineligibleAccountHasAnEligibilityMessageInsteadOfAReconnectHint() {
        assertEquals(R.string.ai_not_eligible, chatErrorMessage(OpenAiFailure("subscription_sharing_user_not_eligible", 403)))
        assertEquals(R.string.ai_sign_in_required, chatErrorMessage(OpenAiFailure("invalid_grant", 400, requiresSignIn = true)))
    }

    @Test
    fun lateTextCannotReappearAfterStopOrInTheNextTurn() {
        val pending = AiChatState().beginTurn("Old question", 1)
        val stopped = pending.discardPendingTurn(false).copy(busy = false)
        assertEquals("", stopped.appendDelta(1, "Old answer").partial)
        val next = stopped.beginTurn("New question", 2)
        assertEquals("", next.appendDelta(1, "Old answer").partial)
        assertEquals("New answer", next.appendDelta(2, "New answer").partial)
        val completed = next.completeTurn("New question", "New answer", false)
        assertEquals("", completed.appendDelta(2, "Late tail").partial)
    }

    @Test
    fun delayedWorkerUpdateRetriesAgainstTheNewTurnsIdentity() {
        val state = MutableStateFlow(AiChatState().beginTurn("Old", 1))
        val readOldState = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val worker =
            Thread {
                try {
                    state.update { current ->
                        val result = current.appendDelta(1, "Old answer")
                        if (first.compareAndSet(true, false)) {
                            readOldState.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        result
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        worker.start()
        try {
            assertTrue(readOldState.await(5, TimeUnit.SECONDS))
            state.update { it.discardPendingTurn(false).beginTurn("New", 2) }
        } finally {
            release.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals("", state.value.partial)
        assertEquals(2, state.value.activeTurnId)
    }
}
