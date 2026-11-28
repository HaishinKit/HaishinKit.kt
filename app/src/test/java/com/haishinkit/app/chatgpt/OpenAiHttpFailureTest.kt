package com.haishinkit.app.chatgpt

import com.haishinkit.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiHttpFailureTest {
    @Test
    fun detailOnlyInferenceForbiddenShowsARestrictionWithoutExposingTheDetail() {
        val request = Request.Builder().url("https://api.openai.com/v1/responses").post("{}".toRequestBody()).build()
        val error = openAiHttpFailure(request, 403, Json.parseToJsonElement("""{"detail":"diagnostic region/policy text"}""").jsonObject)
        assertEquals(403, error.status)
        assertTrue(error.inferenceRestricted)
        assertFalse(error.requiresSignIn)
        assertEquals(R.string.ai_inference_restricted, chatErrorMessage(error))
        assertFalse(error.message.orEmpty().contains("diagnostic"))
    }

    @Test
    fun restrictionMappingDoesNotAffectOAuthMcpOrOtherStatuses() {
        val body = Json.parseToJsonElement("""{"detail":"diagnostic"}""").jsonObject
        for (url in listOf("https://auth.openai.com/api/accounts/oauth/token", "http://127.0.0.1:8081/mcp", "https://api.openai.com/v1/models")) {
            val request = Request.Builder().url(url).post("{}".toRequestBody()).build()
            assertFalse(openAiHttpFailure(request, 403, body).inferenceRestricted)
        }
        val request = Request.Builder().url("https://api.openai.com/v1/responses").post("{}".toRequestBody()).build()
        for (status in listOf(401, 429, 503)) assertFalse(openAiHttpFailure(request, status, body).inferenceRestricted)
    }

    @Test
    fun structuredMachineCodesKeepTheirExistingRecovery() {
        val request = Request.Builder().url("https://api.openai.com/v1/responses").post("{}".toRequestBody()).build()
        val body = Json.parseToJsonElement("""{"error":{"code":"subscription_sharing_user_not_eligible"}}""").jsonObject
        val error = openAiHttpFailure(request, 403, body)
        assertEquals("subscription_sharing_user_not_eligible", error.code)
        assertFalse(error.inferenceRestricted)
        assertEquals(R.string.ai_not_eligible, chatErrorMessage(error))
    }
}
