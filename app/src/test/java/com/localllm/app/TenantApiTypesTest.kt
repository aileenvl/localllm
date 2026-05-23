package com.localllm.app

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TenantApiTypesTest {

    private val gson = Gson()

    @Test
    fun `document responses include tenant_id`() {
        val summary = DocumentSummaryResponse(
            documentId = "foo",
            chunkCount = 3,
            model = "bge-small-en-v1.5",
            tenantId = "app-a",
        )
        val obj = JsonParser.parseString(gson.toJson(summary)).asJsonObject
        assertEquals("foo", obj["document_id"].asString)
        assertEquals("app-a", obj["tenant_id"].asString)

        val list = DocumentListResponse(
            data = listOf(summary),
            tenantId = "app-a",
        )
        val listObj = JsonParser.parseString(gson.toJson(list)).asJsonObject
        assertEquals("app-a", listObj["tenant_id"].asString)

        val deleted = DocumentDeleteResponse(
            documentId = "foo",
            deleted = true,
            chunksRemoved = 3,
            tenantId = "app-a",
        )
        val delObj = JsonParser.parseString(gson.toJson(deleted)).asJsonObject
        assertEquals("app-a", delObj["tenant_id"].asString)
    }

    @Test
    fun `search response includes tenant_id`() {
        val resp = SearchResponse(
            data = emptyList(),
            model = "bge-small-en-v1.5",
            tenantId = "app-b",
        )
        val obj = JsonParser.parseString(gson.toJson(resp)).asJsonObject
        assertEquals("app-b", obj["tenant_id"].asString)
    }

    @Test
    fun `tenant admin responses serialize expected fields`() {
        val list = TenantListResponse(
            data = listOf(
                TenantSummaryResponse(
                    tenantId = "app-a",
                    documentCount = 2,
                    chunkCount = 5,
                ),
            ),
        )
        val listObj = JsonParser.parseString(gson.toJson(list)).asJsonObject
        assertEquals("list", listObj["object"].asString)
        val row = listObj["data"].asJsonArray[0].asJsonObject
        assertEquals("app-a", row["tenant_id"].asString)
        assertEquals(2, row["document_count"].asInt)
        assertEquals(5, row["chunk_count"].asInt)

        val deleted = TenantDeleteResponse(
            tenantId = "app-a",
            deleted = true,
            chunksRemoved = 5,
        )
        val delObj = JsonParser.parseString(gson.toJson(deleted)).asJsonObject
        assertEquals("app-a", delObj["tenant_id"].asString)
        assertTrue(delObj["deleted"].asBoolean)
        assertEquals(5, delObj["chunks_removed"].asInt)
    }
}
