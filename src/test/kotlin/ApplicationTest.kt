package com.mushroom

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ApplicationTest {

    // 每个测试用独立的 H2 内嵌数据库（内存，测试结束自动销毁）
    private lateinit var testDb: Database

    @BeforeTest
    fun setup() {
        testDb = Database.connect(
            url = "jdbc:h2:mem:test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = ""
        )
    }

    // 测试用 Application 配置，注入 H2 数据库，跳过 application.yaml
    private fun ApplicationTestBuilder.setupTestApp() {
        application {
            configureSerialization()
            configureDatabases(testDb)
            configureRouting()
        }
    }

    // -----------------------------------------------------------------------
    // GET /health
    // -----------------------------------------------------------------------

    @Test
    fun `health check returns ok`() = testApplication {
        setupTestApp()
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\":\"ok\""), "body: $body")
    }

    // -----------------------------------------------------------------------
    // POST /ping
    // -----------------------------------------------------------------------

    @Test
    fun `post ping creates record and returns id`() = testApplication {
        setupTestApp()
        val response = client.post("/ping") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonObject
        assertTrue(json.containsKey("id"), "response should contain id, body: $body")
    }

    @Test
    fun `post ping with empty message still creates record`() = testApplication {
        setupTestApp()
        val response = client.post("/ping") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":""}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `post ping without body returns error`() = testApplication {
        setupTestApp()
        val response = client.post("/ping") {
            contentType(ContentType.Application.Json)
        }
        assertTrue(
            response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.UnsupportedMediaType,
            "expected 400 or 415, got ${response.status}"
        )
    }

    // -----------------------------------------------------------------------
    // GET /ping
    // -----------------------------------------------------------------------

    @Test
    fun `get ping returns empty list when no records`() = testApplication {
        setupTestApp()
        val response = client.get("/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val json = Json.parseToJsonElement(body).jsonArray
        assertEquals(0, json.size, "should be empty, body: $body")
    }

    @Test
    fun `get ping returns inserted record`() = testApplication {
        setupTestApp()
        client.post("/ping") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":"test-message"}""")
        }
        val response = client.get("/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, json.size)
        assertEquals("test-message", json[0].jsonObject["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get ping returns records in descending order`() = testApplication {
        setupTestApp()
        listOf("first", "second", "third").forEach { msg ->
            client.post("/ping") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"$msg"}""")
            }
        }
        val response = client.get("/ping")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(3, json.size)
        assertEquals("third", json[0].jsonObject["message"]?.jsonPrimitive?.content)
        assertEquals("first", json[2].jsonObject["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get ping returns at most 10 records`() = testApplication {
        setupTestApp()
        repeat(15) { i ->
            client.post("/ping") {
                contentType(ContentType.Application.Json)
                setBody("""{"message":"msg-$i"}""")
            }
        }
        val response = client.get("/ping")
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(10, json.size, "should only return 10 records")
    }

    // -----------------------------------------------------------------------
    // POST /backup/upload
    // -----------------------------------------------------------------------

    private val sampleBackupJson = """{"schemaVersion":1,"exportedAt":"2026-03-13T10:00:00","tasks":[{"id":1,"title":"test","subject":"math","estimatedMinutes":30,"repeatRuleType":"NONE","repeatRuleDays":null,"date":"2026-03-13","deadlineAt":null,"templateType":null,"status":"ACTIVE","description":""}],"checkIns":[],"mushroomLedger":[],"deductionConfigs":[],"deductionRecords":[],"rewards":[],"rewardExchanges":[],"milestones":[],"scoringRules":[],"keyDates":[]}"""

    @Test
    fun `upload backup returns 201 with id`() = testApplication {
        setupTestApp()
        val response = client.post("/backup/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"test-device","backup":$sampleBackupJson}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(json["id"], "response should contain id")
        assertNotNull(json["createdAt"], "response should contain createdAt")
        assertEquals("2026-03-13T10:00:00", json["exportedAt"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------------
    // GET /backup/list
    // -----------------------------------------------------------------------

    @Test
    fun `list backups returns empty for unknown device`() = testApplication {
        setupTestApp()
        val response = client.get("/backup/list?deviceId=nonexistent")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(0, json.size)
    }

    @Test
    fun `list backups requires deviceId parameter`() = testApplication {
        setupTestApp()
        val response = client.get("/backup/list")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `upload then list returns one backup with correct summary`() = testApplication {
        setupTestApp()
        client.post("/backup/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-1","backup":$sampleBackupJson}""")
        }
        val response = client.get("/backup/list?deviceId=dev-1")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(1, json.size)
        val summary = json[0].jsonObject
        assertEquals(1, summary["taskCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals("2026-03-13T10:00:00", summary["exportedAt"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------------
    // GET /backup/{id}/download
    // -----------------------------------------------------------------------

    @Test
    fun `download backup returns full payload`() = testApplication {
        setupTestApp()
        val uploadResp = client.post("/backup/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-2","backup":$sampleBackupJson}""")
        }
        val uploadJson = Json.parseToJsonElement(uploadResp.bodyAsText()).jsonObject
        val id = uploadJson["id"]?.jsonPrimitive?.content

        val response = client.get("/backup/$id/download")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("dev-2", json["deviceId"]?.jsonPrimitive?.content)
        val backup = json["backup"]?.jsonObject
        assertNotNull(backup, "response should contain backup object")
        assertEquals(1, backup["schemaVersion"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `download nonexistent backup returns 404`() = testApplication {
        setupTestApp()
        val response = client.get("/backup/99999/download")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -----------------------------------------------------------------------
    // DELETE /backup/{id}
    // -----------------------------------------------------------------------

    @Test
    fun `delete backup removes it`() = testApplication {
        setupTestApp()
        val uploadResp = client.post("/backup/upload") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev-3","backup":$sampleBackupJson}""")
        }
        val id = Json.parseToJsonElement(uploadResp.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content

        val deleteResp = client.delete("/backup/$id")
        assertEquals(HttpStatusCode.OK, deleteResp.status)

        val listResp = client.get("/backup/list?deviceId=dev-3")
        val list = Json.parseToJsonElement(listResp.bodyAsText()).jsonArray
        assertEquals(0, list.size, "backup should be gone after delete")
    }

    @Test
    fun `delete nonexistent backup returns 404`() = testApplication {
        setupTestApp()
        val response = client.delete("/backup/99999")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -----------------------------------------------------------------------
    // 5-backup limit per device
    // -----------------------------------------------------------------------

    @Test
    fun `upload enforces 5 backup limit per device`() = testApplication {
        setupTestApp()
        repeat(6) {
            client.post("/backup/upload") {
                contentType(ContentType.Application.Json)
                setBody("""{"deviceId":"dev-limit","backup":$sampleBackupJson}""")
            }
        }
        val response = client.get("/backup/list?deviceId=dev-limit")
        val list = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertEquals(5, list.size, "should keep at most 5 backups per device")
    }
}

