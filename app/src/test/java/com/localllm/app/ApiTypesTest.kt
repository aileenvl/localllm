package com.localllm.app

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the wire shape of the Stage-1 [ApiTypes] additions:
 *   - [Message.content] survives a Gson round-trip in all three shapes
 *     (string, parts array, null) — Option 1 polymorphism.
 *   - `tools` + `tool_choice` deserialize from the OpenAI-verbatim payload.
 *   - Tool-call response shape (assistant message w/ `tool_calls` + null
 *     content, finish_reason "tool_calls") serializes correctly.
 *
 * No Android / LiteRT-LM dependencies — pure JVM.
 */
class ApiTypesTest {

    private val gson = Gson()

    /* ---------- polymorphic content round-trip ---------- */

    @Test
    fun `string content survives a Gson round-trip`() {
        val json = """{"role":"user","content":"hello"}"""
        val parsed = gson.fromJson(json, Message::class.java)
        assertEquals("user", parsed.role)
        assertEquals("hello", parsed.contentString())
        val parts = parsed.contentParts()
        assertEquals(1, parts.size)
        assertTrue(parts[0] is ContentPart.TextPart)
        assertEquals("hello", (parts[0] as ContentPart.TextPart).text)

        // Round-trip: serialize and re-parse, should be identical.
        val reSerialized = gson.toJson(parsed)
        val reParsed = gson.fromJson(reSerialized, Message::class.java)
        assertEquals("hello", reParsed.contentString())
    }

    @Test
    fun `multimodal content array survives a Gson round-trip`() {
        val json = """
            {
              "role":"user",
              "content":[
                {"type":"text","text":"What is in this image?"},
                {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}
              ]
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, Message::class.java)
        assertEquals("user", parsed.role)
        assertNull("array-shaped content is not a string", parsed.contentString())

        val parts = parsed.contentParts()
        assertEquals(2, parts.size)
        assertEquals("What is in this image?", (parts[0] as ContentPart.TextPart).text)
        assertEquals("data:image/jpeg;base64,AAAA", (parts[1] as ContentPart.ImagePart).url)

        // Round-trip — re-serializing should give back the same logical shape.
        val reSerialized = gson.toJson(parsed)
        val reParsed = gson.fromJson(reSerialized, Message::class.java)
        val reParts = reParsed.contentParts()
        assertEquals(2, reParts.size)
        assertEquals("What is in this image?", (reParts[0] as ContentPart.TextPart).text)
        assertEquals("data:image/jpeg;base64,AAAA", (reParts[1] as ContentPart.ImagePart).url)
    }

    @Test
    fun `image_url accepts both string and object forms`() {
        val json = """
            {
              "role":"user",
              "content":[
                {"type":"image_url","image_url":"data:image/png;base64,XXXX"}
              ]
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, Message::class.java)
        val parts = parsed.contentParts()
        assertEquals(1, parts.size)
        assertEquals("data:image/png;base64,XXXX", (parts[0] as ContentPart.ImagePart).url)
    }

    @Test
    fun `null content on an assistant tool-call message survives a round-trip`() {
        val json = """
            {
              "role":"assistant",
              "content":null,
              "tool_calls":[{
                "id":"call_1",
                "type":"function",
                "function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}
              }]
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, Message::class.java)
        // `content: null` deserializes to either a Kotlin null or a JsonNull —
        // both should be treated as "no string content".
        assertNull(parsed.contentString())
        assertEquals(1, parsed.toolCalls?.size)
        val tc = parsed.toolCalls!![0]
        assertEquals("call_1", tc.id)
        assertEquals("get_weather", tc.function.name)
        assertEquals("""{"city":"Paris"}""", tc.function.arguments)

        // Round-trip preserves the shape.
        val reSerialized = gson.toJson(parsed)
        assertTrue("re-serialized payload contains tool_calls", reSerialized.contains("tool_calls"))
        val reParsed = gson.fromJson(reSerialized, Message::class.java)
        assertEquals(1, reParsed.toolCalls?.size)
    }

    @Test
    fun `tool follow-up role survives a round-trip`() {
        val json = """
            {
              "role":"tool",
              "tool_call_id":"call_1",
              "content":"{\"temp_c\":18}"
            }
        """.trimIndent()
        val parsed = gson.fromJson(json, Message::class.java)
        assertEquals("tool", parsed.role)
        assertEquals("call_1", parsed.toolCallId)
        assertEquals("""{"temp_c":18}""", parsed.contentString())
    }

    /* ---------- tools + tool_choice ---------- */

    @Test
    fun `ChatRequest deserializes the OpenAI tools envelope`() {
        val json = """
            {
              "model":"gemma-4-e2b",
              "messages":[{"role":"user","content":"What's the weather in Paris?"}],
              "tools":[{
                "type":"function",
                "function":{
                  "name":"get_weather",
                  "description":"Get the current weather in a city.",
                  "parameters":{
                    "type":"object",
                    "properties":{"city":{"type":"string"}},
                    "required":["city"]
                  }
                }
              }],
              "tool_choice":"auto"
            }
        """.trimIndent()
        val req = gson.fromJson(json, ChatRequest::class.java)
        assertEquals(1, req.tools?.size)
        val tool = req.tools!![0]
        assertEquals("function", tool.type)
        assertEquals("get_weather", tool.function.name)
        assertEquals("Get the current weather in a city.", tool.function.description)
        assertEquals("object", tool.function.parameters["type"].asString)
        assertNotNull(req.toolChoice)
        assertTrue(req.toolChoice!!.isJsonPrimitive)
        assertEquals("auto", req.toolChoice!!.asString)
    }

    @Test
    fun `tool_choice accepts both string and object forms`() {
        val stringForm = gson.fromJson(
            """{"model":"m","messages":[],"tool_choice":"none"}""",
            ChatRequest::class.java
        )
        assertEquals("none", stringForm.toolChoice!!.asString)

        val objectForm = gson.fromJson(
            """{"model":"m","messages":[],"tool_choice":{"type":"function","function":{"name":"x"}}}""",
            ChatRequest::class.java
        )
        assertTrue(objectForm.toolChoice!!.isJsonObject)
        assertEquals(
            "x",
            objectForm.toolChoice!!.asJsonObject["function"].asJsonObject["name"].asString
        )
    }

    /* ---------- tool-call response shape ---------- */

    @Test
    fun `tool-call assistant response serializes with the expected fields`() {
        val msg = Message(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ToolCallApi(
                    id = "call_abc123",
                    type = "function",
                    function = ToolCallFunction(
                        name = "get_weather",
                        arguments = """{"city":"Paris"}""",
                    ),
                )
            ),
        )
        val resp = ChatResponse(
            id = "chatcmpl-1",
            `object` = "chat.completion",
            created = 0L,
            model = "gemma-4-e2b",
            choices = listOf(Choice(index = 0, message = msg, finishReason = "tool_calls"))
        )
        val json = gson.toJson(resp)
        val parsed = JsonParser.parseString(json).asJsonObject
        val choice = parsed["choices"].asJsonArray[0].asJsonObject
        assertEquals("tool_calls", choice["finish_reason"].asString)
        val emittedMsg = choice["message"].asJsonObject
        assertEquals("assistant", emittedMsg["role"].asString)
        // `content` may be omitted entirely or emitted as null — Gson omits
        // null fields by default, so either is acceptable. The crucial part
        // is that there is no spurious empty string.
        if (emittedMsg.has("content")) {
            assertTrue(
                "content must be null when omitted is not the choice",
                emittedMsg["content"].isJsonNull
            )
        }
        val tcs = emittedMsg["tool_calls"].asJsonArray
        assertEquals(1, tcs.size())
        val tc = tcs[0].asJsonObject
        assertEquals("call_abc123", tc["id"].asString)
        assertEquals("function", tc["type"].asString)
        assertEquals("get_weather", tc["function"].asJsonObject["name"].asString)
        assertEquals(
            """{"city":"Paris"}""",
            tc["function"].asJsonObject["arguments"].asString
        )
    }

    /* ---------- partsContent helper round-trip ---------- */

    @Test
    fun `partsContent helper produces JSON that parses back to the same parts`() {
        val parts = listOf(
            ContentPart.TextPart("hi"),
            ContentPart.ImagePart("data:image/jpeg;base64,ZZZZ"),
        )
        val msg = Message(role = "user", content = partsContent(parts))
        val json = gson.toJson(msg)
        val reParsed = gson.fromJson(json, Message::class.java)
        val reParts = reParsed.contentParts()
        assertEquals(2, reParts.size)
        assertEquals("hi", (reParts[0] as ContentPart.TextPart).text)
        assertEquals("data:image/jpeg;base64,ZZZZ", (reParts[1] as ContentPart.ImagePart).url)
    }

    /* ---------- textChars covers both shapes ---------- */

    @Test
    fun `textChars counts characters across both shapes`() {
        val stringMsg = gson.fromJson("""{"role":"user","content":"hello"}""", Message::class.java)
        assertEquals(5, stringMsg.textChars())

        val arrayMsg = gson.fromJson(
            """{"role":"user","content":[{"type":"text","text":"abcd"},{"type":"image_url","image_url":"data:,"}]}""",
            Message::class.java
        )
        // Image parts don't contribute character count.
        assertEquals(4, arrayMsg.textChars())

        val nullMsg = Message(role = "assistant", content = null)
        assertEquals(0, nullMsg.textChars())
    }

    /* ---------- legacy v1.1.0 string contract still works ---------- */

    @Test
    fun `legacy text-only ChatRequest still deserializes (v1_1_0 backwards-compat)`() {
        val json = """
            {
              "model":"gemma-4-e2b",
              "messages":[
                {"role":"system","content":"be brief"},
                {"role":"user","content":"hi"}
              ],
              "stream":true,
              "session_id":"s-1",
              "temperature":0.5,
              "top_k":40,
              "max_tokens":256
            }
        """.trimIndent()
        val req = gson.fromJson(json, ChatRequest::class.java)
        assertEquals("gemma-4-e2b", req.model)
        assertEquals(2, req.messages.size)
        assertEquals("be brief", req.messages[0].contentString())
        assertEquals("hi", req.messages[1].contentString())
        assertEquals(true, req.stream)
        assertEquals("s-1", req.sessionId)
        assertEquals(0.5f, req.temperature)
        assertEquals(40, req.topK)
        assertEquals(256, req.maxTokens)
        // Optional fields default to null.
        assertNull(req.tools)
        assertNull(req.toolChoice)
    }
}
