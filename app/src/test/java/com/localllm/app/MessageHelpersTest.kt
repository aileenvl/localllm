package com.localllm.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests for the helpers extracted into [MessageHelpers] — the bits
 * of the Stage-1 work that aren't tied to an Android Service instance or the
 * LiteRT-LM JNI. Everything here runs in plain JUnit, no Robolectric required.
 *
 * Coverage targets:
 *  - [messagesPrefixHash] — stability, role / content / tool-field sensitivity.
 *  - [isLoopbackHttpUrl]  — SSRF allowlist correctness (positive + negative).
 *  - [decodeDataImageUrl] — base64 happy path, malformed input handling.
 *  - [parseToolArguments] — well-formed JSON, malformed JSON, non-object JSON.
 *  - [jsonToAny]          — primitive / array / object / null mapping.
 *  - [buildToolDescriptionJson] — OpenAI-envelope shape.
 */
class MessageHelpersTest {

    private val gson = Gson()

    // ---------------------------------------------------------------- prefix hash

    @Test
    fun `messagesPrefixHash is stable across identical inputs`() {
        val msgs = listOf(
            msg("user", "hi"),
            msg("assistant", "hello"),
        )
        assertEquals(messagesPrefixHash(msgs, 2), messagesPrefixHash(msgs, 2))
    }

    @Test
    fun `messagesPrefixHash discriminates on role`() {
        val a = listOf(msg("user", "hi"))
        val b = listOf(msg("assistant", "hi"))
        assertNotEquals(messagesPrefixHash(a, 1), messagesPrefixHash(b, 1))
    }

    @Test
    fun `messagesPrefixHash discriminates on content`() {
        val a = listOf(msg("user", "hello"))
        val b = listOf(msg("user", "hi"))
        assertNotEquals(messagesPrefixHash(a, 1), messagesPrefixHash(b, 1))
    }

    @Test
    fun `messagesPrefixHash discriminates on tool_call_id`() {
        val a = listOf(Message(role = "tool", content = jsonPrim("ok"), toolCallId = "call_1"))
        val b = listOf(Message(role = "tool", content = jsonPrim("ok"), toolCallId = "call_2"))
        assertNotEquals(messagesPrefixHash(a, 1), messagesPrefixHash(b, 1))
    }

    @Test
    fun `messagesPrefixHash discriminates on tool_calls`() {
        val a = listOf(
            Message(
                role = "assistant",
                toolCalls = listOf(
                    ToolCallApi("call_1", "function", ToolCallFunction("get_weather", """{"city":"Paris"}"""))
                )
            )
        )
        val b = listOf(
            Message(
                role = "assistant",
                toolCalls = listOf(
                    ToolCallApi("call_1", "function", ToolCallFunction("get_weather", """{"city":"London"}"""))
                )
            )
        )
        assertNotEquals(messagesPrefixHash(a, 1), messagesPrefixHash(b, 1))
    }

    @Test
    fun `messagesPrefixHash respects the count parameter`() {
        val msgs = listOf(msg("user", "hi"), msg("assistant", "hello"), msg("user", "again"))
        val firstOnly = messagesPrefixHash(msgs, 1)
        val firstTwo = messagesPrefixHash(msgs, 2)
        val all = messagesPrefixHash(msgs, 3)
        assertNotEquals(firstOnly, firstTwo)
        assertNotEquals(firstTwo, all)
        // Same first message → first-only hash matches a single-message list.
        assertEquals(firstOnly, messagesPrefixHash(listOf(msgs[0]), 1))
    }

    @Test
    fun `messagesPrefixHash count is clamped to the list size`() {
        val msgs = listOf(msg("user", "hi"))
        // count = 10 should behave the same as count = 1 (the only message present).
        assertEquals(messagesPrefixHash(msgs, 1), messagesPrefixHash(msgs, 10))
    }

    // ---------------------------------------------------------------- SSRF allowlist

    @Test
    fun `isLoopbackHttpUrl accepts the three loopback hosts`() {
        assertTrue(isLoopbackHttpUrl("http://localhost/img.png"))
        assertTrue(isLoopbackHttpUrl("http://127.0.0.1/img.png"))
        assertTrue(isLoopbackHttpUrl("http://[::1]/img.png"))
    }

    @Test
    fun `isLoopbackHttpUrl accepts loopback with a port`() {
        assertTrue(isLoopbackHttpUrl("http://localhost:8099/foo/bar"))
        assertTrue(isLoopbackHttpUrl("http://127.0.0.1:8080/x"))
    }

    @Test
    fun `isLoopbackHttpUrl accepts loopback case-insensitively`() {
        assertTrue(isLoopbackHttpUrl("http://LocalHost/x"))
        assertTrue(isLoopbackHttpUrl("http://LOCALHOST:8099/y"))
    }

    @Test
    fun `isLoopbackHttpUrl rejects public hosts`() {
        assertFalse(isLoopbackHttpUrl("http://example.com/img.png"))
        assertFalse(isLoopbackHttpUrl("http://10.0.0.1/img.png"))
        assertFalse(isLoopbackHttpUrl("http://192.168.1.1/img.png"))
        // 0.0.0.0 looks loopback-adjacent but can route to any-interface — reject.
        assertFalse(isLoopbackHttpUrl("http://0.0.0.0/img.png"))
    }

    @Test
    fun `isLoopbackHttpUrl rejects https even when host is loopback`() {
        assertFalse(isLoopbackHttpUrl("https://localhost/img.png"))
    }

    @Test
    fun `isLoopbackHttpUrl rejects non-http schemes`() {
        assertFalse(isLoopbackHttpUrl("file:///etc/passwd"))
        assertFalse(isLoopbackHttpUrl("ftp://localhost/x"))
        assertFalse(isLoopbackHttpUrl("data:image/png;base64,AAA"))
        assertFalse(isLoopbackHttpUrl("javascript:alert(1)"))
    }

    @Test
    fun `isLoopbackHttpUrl handles host parameter parsed before path`() {
        // Trailing path doesn't change host extraction.
        assertTrue(isLoopbackHttpUrl("http://localhost/a/b/c?d=e&f=g"))
        // Userinfo prefix shouldn't fool the parser into accepting a public host.
        // (We don't currently support userinfo; trailing host wins.)
        assertFalse(isLoopbackHttpUrl("http://localhost@example.com/x"))
    }

    // ---------------------------------------------------------------- data: URL decode

    @Test
    fun `decodeDataImageUrl decodes a valid base64 payload`() {
        // "Hello" in base64 is "SGVsbG8="
        val out = decodeDataImageUrl("data:image/jpeg;base64,SGVsbG8=")
        assertEquals("Hello", String(out))
    }

    @Test
    fun `decodeDataImageUrl handles png mime`() {
        val out = decodeDataImageUrl("data:image/png;base64,SGVsbG8=")
        assertEquals("Hello", String(out))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeDataImageUrl rejects a non-data URL`() {
        decodeDataImageUrl("https://example.com/x.png")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeDataImageUrl rejects when base64 marker is missing`() {
        decodeDataImageUrl("data:image/jpeg,Hello")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeDataImageUrl rejects an empty base64 payload`() {
        decodeDataImageUrl("data:image/jpeg;base64,")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeDataImageUrl rejects malformed base64`() {
        // '@' is not a valid base64 character.
        decodeDataImageUrl("data:image/jpeg;base64,####@@@@")
    }

    // ---------------------------------------------------------------- tool argument parsing

    @Test
    fun `parseToolArguments returns a map for valid JSON object input`() {
        val args = parseToolArguments("""{"city":"Paris","celsius":true}""")
        assertEquals(2, args.size)
        assertEquals("Paris", args["city"])
        assertEquals(true, args["celsius"])
    }

    @Test
    fun `parseToolArguments handles nested objects and arrays`() {
        val args = parseToolArguments(
            """{"filters":{"min":0,"max":10},"tags":["weather","outdoor"]}"""
        )
        @Suppress("UNCHECKED_CAST")
        val filters = args["filters"] as Map<String, Any>
        assertEquals(2, filters.size)
        @Suppress("UNCHECKED_CAST")
        val tags = args["tags"] as List<Any>
        assertEquals(2, tags.size)
        assertEquals("weather", tags[0])
    }

    @Test
    fun `parseToolArguments returns empty for blank input`() {
        assertTrue(parseToolArguments("").isEmpty())
        assertTrue(parseToolArguments("   ").isEmpty())
    }

    @Test
    fun `parseToolArguments returns empty for malformed JSON`() {
        // No exception — graceful degradation to empty arguments.
        assertTrue(parseToolArguments("not valid json").isEmpty())
        assertTrue(parseToolArguments("{unclosed").isEmpty())
    }

    @Test
    fun `parseToolArguments returns empty for non-object JSON`() {
        // OpenAI's contract is that `function.arguments` is always a JSON object.
        // Anything else (array, primitive) → empty map.
        assertTrue(parseToolArguments("[1, 2, 3]").isEmpty())
        assertTrue(parseToolArguments(""""just a string"""").isEmpty())
        assertTrue(parseToolArguments("42").isEmpty())
    }

    // ---------------------------------------------------------------- jsonToAny

    @Test
    fun `jsonToAny maps primitives`() {
        assertEquals("hello", jsonToAny(JsonParser.parseString(""""hello"""")))
        assertEquals(true, jsonToAny(JsonParser.parseString("true")))
        // Numbers come back as kotlin.Number; the agent doesn't commit to a
        // specific subclass. Just verify the numeric value.
        val n = jsonToAny(JsonParser.parseString("42"))
        assertEquals(42, (n as Number).toInt())
    }

    @Test
    fun `jsonToAny maps null to empty string`() {
        // LiteRT-LM's ToolCall(name, arguments: Map<String, Any>) doesn't
        // tolerate null values — we coerce nulls to empty strings.
        assertEquals("", jsonToAny(JsonParser.parseString("null")))
    }

    @Test
    fun `jsonToAny maps arrays and objects recursively`() {
        @Suppress("UNCHECKED_CAST")
        val arr = jsonToAny(JsonParser.parseString("""[1, "two", true]""")) as List<Any>
        assertEquals(3, arr.size)
        assertEquals(1, (arr[0] as Number).toInt())
        assertEquals("two", arr[1])
        assertEquals(true, arr[2])

        @Suppress("UNCHECKED_CAST")
        val obj = jsonToAny(JsonParser.parseString("""{"k":"v"}""")) as Map<String, Any>
        assertEquals("v", obj["k"])
    }

    // ---------------------------------------------------------------- tool description JSON

    @Test
    fun `buildToolDescriptionJson produces the OpenAI envelope shape`() {
        val params = JsonParser.parseString(
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        ).asJsonObject
        val json = buildToolDescriptionJson(
            name = "get_weather",
            description = "Get the current weather in a city.",
            parameters = params
        )
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("get_weather", parsed["name"].asString)
        assertEquals("Get the current weather in a city.", parsed["description"].asString)
        assertEquals(
            "object",
            parsed["parameters"].asJsonObject["type"].asString
        )
        assertTrue(parsed["parameters"].asJsonObject.has("properties"))
    }

    @Test
    fun `buildToolDescriptionJson treats a null description as an empty string`() {
        val params = JsonObject().apply { addProperty("type", "object") }
        val json = buildToolDescriptionJson(name = "no_op", description = null, parameters = params)
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("", parsed["description"].asString)
    }

    // ---------------------------------------------------------------- helpers

    private fun msg(role: String, text: String): Message =
        Message(role = role, content = jsonPrim(text))

    private fun jsonPrim(text: String) =
        JsonParser.parseString(gson.toJson(text))   // becomes a JsonPrimitive
}
