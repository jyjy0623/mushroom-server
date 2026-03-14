package com.mushroom

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FriendsTest {

    private lateinit var testDb: Database

    private val jwtSecret = "test-secret"
    private val jwtIssuer = "test-issuer"
    private val jwtAudience = "test-audience"

    @BeforeTest
    fun setup() {
        testDb = Database.connect(
            url = "jdbc:h2:mem:friends_test_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
            user = "sa",
            password = ""
        )
        // 预建表，使 createUser 可在 setupTestApp 之前调用
        transaction(testDb) {
            SchemaUtils.createMissingTablesAndColumns(
                PingTable, UsersTable, RefreshTokensTable,
                CloudBackupTable, LeaderboardTable, FriendsTable
            )
        }
    }

    private fun ApplicationTestBuilder.setupTestApp() {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "jwt.secret" to jwtSecret,
                "jwt.issuer" to jwtIssuer,
                "jwt.audience" to jwtAudience,
                "jwt.realm" to "test",
                "jwt.accessTokenExpireMinutes" to "120",
                "jwt.refreshTokenExpireDays" to "30"
            )
        }
        application {
            configureSerialization()
            configureDatabases(testDb)
            configureAuth()
            configureAuthRoutes()
            configureFriendRoutes()
            configureLeaderboardRoutes()
        }
    }

    private fun generateToken(userId: Int): String = JWT.create()
        .withAudience(jwtAudience)
        .withIssuer(jwtIssuer)
        .withClaim("userId", userId)
        .withExpiresAt(Date(System.currentTimeMillis() + 3600_000))
        .sign(Algorithm.HMAC256(jwtSecret))

    /** 注册用户并返回 userId */
    private fun createUser(phone: String, nickname: String = "测试用户"): Int {
        return transaction(testDb) {
            val now = System.currentTimeMillis()
            UsersTable.insert {
                it[UsersTable.phone] = phone
                it[UsersTable.nickname] = nickname
                it[UsersTable.avatarUrl] = ""
                it[UsersTable.createdAt] = now
                it[UsersTable.updatedAt] = now
            } get UsersTable.id
        }
    }

    // -----------------------------------------------------------------------
    // POST /friend/add
    // -----------------------------------------------------------------------

    @Test
    fun `add friend by phone returns success`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001", "用户A")
        val userB = createUser("13800000002", "用户B")
        val token = generateToken(userA)

        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13800000002"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(json["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("好友添加成功！", json["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `add friend with invalid phone returns 400`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"12345"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, json["success"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `add friend with nonexistent phone returns 404`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13999999999"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `cannot add self as friend`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13800000001"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("不能添加自己为好友", json["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `add friend twice returns already friends`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        createUser("13800000002")
        val token = generateToken(userA)

        // 第一次添加
        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13800000002"}""")
        }

        // 第二次添加
        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13800000002"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, json["success"]?.jsonPrimitive?.boolean)
        assertEquals("已经是好友了", json["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `add friend creates bidirectional relationship`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001", "用户A")
        val userB = createUser("13800000002", "用户B")
        val tokenA = generateToken(userA)
        val tokenB = generateToken(userB)

        // A 添加 B
        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenA)
            setBody("""{"phone":"13800000002"}""")
        }

        // A 的好友列表应该有 B
        val listA = client.get("/friend/list") { bearerAuth(tokenA) }
        val friendsA = Json.parseToJsonElement(listA.bodyAsText()).jsonObject["friends"]?.jsonArray
        assertEquals(1, friendsA?.size)
        assertEquals("用户B", friendsA?.get(0)?.jsonObject?.get("nickname")?.jsonPrimitive?.content)

        // B 的好友列表也应该有 A（双向）
        val listB = client.get("/friend/list") { bearerAuth(tokenB) }
        val friendsB = Json.parseToJsonElement(listB.bodyAsText()).jsonObject["friends"]?.jsonArray
        assertEquals(1, friendsB?.size)
        assertEquals("用户A", friendsB?.get(0)?.jsonObject?.get("nickname")?.jsonPrimitive?.content)
    }

    @Test
    fun `add friend without auth returns 401`() = testApplication {
        setupTestApp()
        val response = client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"13800000002"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -----------------------------------------------------------------------
    // GET /friend/list
    // -----------------------------------------------------------------------

    @Test
    fun `friend list returns empty when no friends`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.get("/friend/list") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val friends = json["friends"]?.jsonArray
        assertEquals(0, friends?.size)
    }

    @Test
    fun `friend list returns friends with masked phone`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        createUser("13812345678", "小明")
        val token = generateToken(userA)

        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{"phone":"13812345678"}""")
        }

        val response = client.get("/friend/list") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val friends = Json.parseToJsonElement(response.bodyAsText()).jsonObject["friends"]?.jsonArray
        assertEquals(1, friends?.size)
        val friend = friends?.get(0)?.jsonObject
        assertEquals("小明", friend?.get("nickname")?.jsonPrimitive?.content)
        assertEquals("138****5678", friend?.get("maskedPhone")?.jsonPrimitive?.content)
    }

    @Test
    fun `friend list without auth returns 401`() = testApplication {
        setupTestApp()
        val response = client.get("/friend/list")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -----------------------------------------------------------------------
    // DELETE /friend/{targetUserId}
    // -----------------------------------------------------------------------

    @Test
    fun `delete friend removes bidirectional relationship`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val userB = createUser("13800000002")
        val tokenA = generateToken(userA)
        val tokenB = generateToken(userB)

        // 添加好友
        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenA)
            setBody("""{"phone":"13800000002"}""")
        }

        // A 删除好友 B
        val deleteResp = client.delete("/friend/$userB") { bearerAuth(tokenA) }
        assertEquals(HttpStatusCode.OK, deleteResp.status)

        // A 的好友列表应为空
        val listA = client.get("/friend/list") { bearerAuth(tokenA) }
        val friendsA = Json.parseToJsonElement(listA.bodyAsText()).jsonObject["friends"]?.jsonArray
        assertEquals(0, friendsA?.size)

        // B 的好友列表也应为空（双向删除）
        val listB = client.get("/friend/list") { bearerAuth(tokenB) }
        val friendsB = Json.parseToJsonElement(listB.bodyAsText()).jsonObject["friends"]?.jsonArray
        assertEquals(0, friendsB?.size)
    }

    @Test
    fun `delete friend with invalid id returns 400`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.delete("/friend/abc") { bearerAuth(token) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `delete friend without auth returns 401`() = testApplication {
        setupTestApp()
        val response = client.delete("/friend/1")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -----------------------------------------------------------------------
    // GET /rank/friends — 好友排行榜
    // -----------------------------------------------------------------------

    @Test
    fun `friend leaderboard returns empty when no friends have scores`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001")
        val token = generateToken(userA)

        val response = client.get("/rank/friends?gameType=runner") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val entries = json["entries"]?.jsonArray
        assertEquals(0, entries?.size)
    }

    @Test
    fun `friend leaderboard includes self and friends sorted by score`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001", "用户A")
        val userB = createUser("13800000002", "用户B")
        val tokenA = generateToken(userA)

        // A 添加 B 为好友
        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenA)
            setBody("""{"phone":"13800000002"}""")
        }

        // A 提交分数 100
        client.post("/rank/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenA)
            setBody("""{"gameType":"runner","score":100}""")
        }

        // B 提交分数 200
        val tokenB = generateToken(userB)
        client.post("/rank/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenB)
            setBody("""{"gameType":"runner","score":200}""")
        }

        // 查询好友排行
        val response = client.get("/rank/friends?gameType=runner") { bearerAuth(tokenA) }
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val entries = json["entries"]?.jsonArray
        assertEquals(2, entries?.size)
        // B 分数高，排第一
        assertEquals("用户B", entries?.get(0)?.jsonObject?.get("nickname")?.jsonPrimitive?.content)
        assertEquals("用户A", entries?.get(1)?.jsonObject?.get("nickname")?.jsonPrimitive?.content)
    }

    @Test
    fun `friend leaderboard does not include non-friends`() = testApplication {
        setupTestApp()
        val userA = createUser("13800000001", "用户A")
        val userB = createUser("13800000002", "用户B")
        val userC = createUser("13800000003", "用户C")
        val tokenA = generateToken(userA)
        val tokenB = generateToken(userB)
        val tokenC = generateToken(userC)

        // A 添加 B 为好友（不添加 C）
        client.post("/friend/add") {
            contentType(ContentType.Application.Json)
            bearerAuth(tokenA)
            setBody("""{"phone":"13800000002"}""")
        }

        // 三人都提交分数
        listOf(tokenA to 100, tokenB to 200, tokenC to 300).forEach { (token, score) ->
            client.post("/rank/submit") {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                setBody("""{"gameType":"runner","score":$score}""")
            }
        }

        // A 的好友排行不应包含 C
        val response = client.get("/rank/friends?gameType=runner") { bearerAuth(tokenA) }
        val entries = Json.parseToJsonElement(response.bodyAsText()).jsonObject["entries"]?.jsonArray
        assertEquals(2, entries?.size)
        val nicknames = entries?.map { it.jsonObject["nickname"]?.jsonPrimitive?.content }
        assertTrue("用户A" in nicknames!!)
        assertTrue("用户B" in nicknames)
        assertTrue("用户C" !in nicknames)
    }
}
