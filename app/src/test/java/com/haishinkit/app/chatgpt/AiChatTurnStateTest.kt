package com.haishinkit.app.chatgpt

import com.haishinkit.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatTurnStateTest {
    @Test
    fun failuresAndStopsNeverCommitQuestionsOrDisableTheFirstModelPicker() {
        var state = AiChatState(model = "model")
        repeat(20) {
            state = state.beginTurn("Uncompleted $it").copy(imageApproved = true)
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
            state = state.beginTurn("Q$turn").completeTurn("Q$turn", "A$turn", hasImages = turn < 6)
        }
        assertEquals(12, state.lines.size)
        assertEquals(ChatLine(true, "Q4"), state.lines.first())
        assertEquals(ChatLine(false, "A9"), state.lines.last())
        repeat(10) { state = state.beginTurn("Failed").discardPendingTurn(false).copy(busy = false) }
        assertEquals(12, state.lines.size)
        assertFalse(state.imageApproved)
    }

    @Test
    fun ineligibleAccountHasAnEligibilityMessageInsteadOfAReconnectHint() {
        assertEquals(R.string.ai_not_eligible, chatErrorMessage(OpenAiFailure("subscription_sharing_user_not_eligible", 403)))
        assertEquals(R.string.ai_sign_in_required, chatErrorMessage(OpenAiFailure("invalid_grant", 400, requiresSignIn = true)))
    }
}
