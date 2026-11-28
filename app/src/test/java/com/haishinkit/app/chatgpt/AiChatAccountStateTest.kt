package com.haishinkit.app.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatAccountStateTest {
    @Test
    fun newlyAuthenticatedAccountCannotSendWithThePreviousAccountsModel() {
        val accountA = AiChatState(account = "account-a", models = listOf(ChatModel("a-only", "A model")), model = "a-only")
        // Authentication can finish before the new account's catalog request fails or is stopped.
        val accountB = accountA.withAccount("account-b")
        assertEquals("account-b", accountB.account)
        assertTrue(accountB.models.isEmpty())
        assertNull(accountB.model)
    }

    @Test
    fun refreshingTheSameAccountKeepsItsSelectedModel() {
        val account = AiChatState(account = "account-a", models = listOf(ChatModel("a-only", "A model")), model = "a-only")
        assertEquals(account, account.withAccount("account-a"))
        assertNull(account.withAccount(null).model)
    }
}
