package com.haishinkit.app.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptConversationTest {
    private val capture = json("""{"output":[{"type":"reasoning","id":"r1","encrypted_content":"opaque"},{"type":"function_call","name":"capture_frame","namespace":"haishinkit","call_id":"c1","arguments":"{}"}]}""")
    private val answer = json("""{"output":[{"type":"message","content":[{"type":"output_text","text":"A product box."}]}]}""")

    @Test
    fun approvedCaptureUsesLocalToolThenSendsImageAndEncryptedReasoning() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val http =
                ChatGptInference { token, body, _ ->
                    assertEquals("oauth-only", token)
                    requests.add(body)
                    if (requests.size == 1) capture else answer
                }
            var executions = 0
            val chat = ChatGptConversation(http)
            val result =
                chat.send(
                    "account-model",
                    "What is this?",
                    { "oauth-only" },
                    JsonArray(emptyList()),
                    execute = { name, _ ->
                        assertEquals("capture_frame", name)
                        executions++
                        McpToolReply("Captured", listOf("data:image/jpeg;base64,aW1hZ2U="))
                    },
                    approve = { true },
                    onDelta = {},
                    onImage = {},
                )
            assertEquals("A product box.", result)
            assertEquals(1, executions)
            assertEquals(JsonPrimitive(false), requests[0]["store"])
            assertEquals(JsonPrimitive(true), requests[0]["stream"])
            val input = requests[1]["input"]!!.jsonArray.toString()
            assertTrue(input.contains("encrypted_content"))
            assertTrue(input.contains("input_image"))
            assertTrue(input.contains("function_call_output"))
            chat.clear()
            chat.send("account-model", "Hello", { "oauth-only" }, JsonArray(emptyList()), { _, _ -> error("Unexpected tool") }, { false }, {}, {})
            assertFalse(requests.last()["input"].toString().contains("input_image"))
        }

    @Test
    fun declinedCaptureNeverReadsCameraOrSendsImage() =
        runBlocking {
            val requests = mutableListOf<JsonObject>()
            val chat =
                ChatGptConversation(
                    ChatGptInference { _, body, _ ->
                        requests.add(body)
                        if (requests.size == 1) capture else answer
                    },
                )
            chat.send("model", "Look", { "token" }, JsonArray(emptyList()), { _, _ -> error("Camera must not execute") }, { false }, {}, { error("No image expected") })
            assertTrue(requests[1]["input"].toString().contains("declined"))
            assertFalse(requests[1]["input"].toString().contains("input_image"))
        }

    @Test
    fun unknownToolsAreRejectedAndFailedTurnsAreNotRetained() {
        val requests = mutableListOf<JsonObject>()
        val chat =
            ChatGptConversation(
                ChatGptInference { _, body, _ ->
                    requests.add(body)
                    if (requests.size == 1) json(capture.toString().replace("capture_frame", "delete_everything")) else answer
                },
            )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chat.send("model", "bad turn", { "token" }, JsonArray(emptyList()), { _, _ -> error("Unknown tool must not execute") }, { true }, {}, {}) }
        }
        runBlocking { chat.send("model", "good turn", { "token" }, JsonArray(emptyList()), { _, _ -> error("Unexpected tool") }, { true }, {}, {}) }
        assertFalse(requests.last()["input"].toString().contains("bad turn"))
    }

    @Test
    fun toolRoundLimitDoesNotPerformAnUnusedCapture() {
        var captures = 0
        var requests = 0
        val chat =
            ChatGptConversation(
                ChatGptInference { _, _, _ ->
                    requests++
                    capture
                },
            )
        val error =
            assertThrows(OpenAiFailure::class.java) {
                runBlocking {
                    chat.send("model", "Look", { "token" }, JsonArray(emptyList()), { _, _ ->
                        captures++
                        McpToolReply("Captured")
                    }, { true }, {}, {})
                }
            }
        assertEquals("tool_round_limit", error.code)
        assertEquals(4, captures)
        assertEquals(5, requests)
    }

    @Test
    fun replayPreservesAssistantPhasesAndToolOrderingAcrossTurns() =
        runBlocking {
            val first = json("""{"output":[{"type":"reasoning","id":"r1","encrypted_content":"opaque"},{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"Checking the camera."}]},{"type":"function_call","name":"hello_android","namespace":"haishinkit","call_id":"c1","arguments":"{}"}]}""")
            val final = json("""{"output":[{"type":"message","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Connected."}]}]}""")
            val requests = mutableListOf<JsonObject>()
            val chat =
                ChatGptConversation(
                    ChatGptInference { _, body, _ ->
                        requests.add(body)
                        if (requests.size == 1) first else final
                    },
                )
            assertEquals("Connected.", chat.send("model", "Check connection", { "token" }, JsonArray(emptyList()), { _, _ -> McpToolReply("Connected") }, { error("No image approval expected") }, {}, {}))
            assertEquals(first["output"]!!.jsonArray.toList(), requests[1]["input"]!!.jsonArray.drop(1).take(3))
            chat.send("model", "What next?", { "token" }, JsonArray(emptyList()), { _, _ -> error("Unexpected tool") }, { false }, {}, {})
            val history = requests[2]["input"]!!.jsonArray
            assertEquals(first["output"]!!.jsonArray.toList(), history.drop(1).take(3))
            assertEquals(final["output"]!!.jsonArray.single(), history[5])
        }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
