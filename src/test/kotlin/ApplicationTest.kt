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
}

