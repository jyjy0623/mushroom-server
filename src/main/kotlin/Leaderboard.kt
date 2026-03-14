package com.mushroom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

// --- Request / Response models ---

@Serializable
data class SubmitScoreRequest(val gameType: String, val score: Int)

@Serializable
data class SubmitScoreResponse(val success: Boolean, val rank: Int? = null)

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val userId: Int,
    val nickname: String,
    val score: Int,
    val createdAt: Long
)

@Serializable
data class LeaderboardResponse(
    val entries: List<LeaderboardEntry>,
    val myEntry: LeaderboardEntry? = null
)

// --- Leaderboard routes ---

fun Application.configureLeaderboardRoutes() {
    routing {
        route("/rank") {

            // POST /rank/submit — 提交分数（需要 JWT）
            authenticate("auth-jwt") {
                post("/submit") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val req = call.receive<SubmitScoreRequest>()

                    if (req.score < 0 || req.score > 99999) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "分数超出合理范围"))
                        return@post
                    }

                    // 获取用户昵称
                    val nickname = transaction {
                        UsersTable.selectAll()
                            .where { UsersTable.id eq userId }
                            .firstOrNull()?.get(UsersTable.nickname) ?: ""
                    }

                    val now = System.currentTimeMillis()

                    // 每用户每游戏类型只保留最高分：存在则更新（仅高分覆盖），不存在则插入
                    transaction {
                        val existing = LeaderboardTable.selectAll()
                            .where {
                                (LeaderboardTable.userId eq userId) and
                                        (LeaderboardTable.gameType eq req.gameType)
                            }
                            .firstOrNull()

                        if (existing != null) {
                            if (req.score > existing[LeaderboardTable.score]) {
                                LeaderboardTable.update({
                                    (LeaderboardTable.userId eq userId) and
                                            (LeaderboardTable.gameType eq req.gameType)
                                }) {
                                    it[score] = req.score
                                    it[LeaderboardTable.nickname] = nickname
                                    it[updatedAt] = now
                                }
                            }
                        } else {
                            LeaderboardTable.insert {
                                it[LeaderboardTable.userId] = userId
                                it[LeaderboardTable.nickname] = nickname
                                it[gameType] = req.gameType
                                it[score] = req.score
                                it[createdAt] = now
                                it[updatedAt] = now
                            }
                        }
                    }

                    // 计算当前排名
                    val rank = transaction {
                        val myScore = LeaderboardTable.selectAll()
                            .where {
                                (LeaderboardTable.userId eq userId) and
                                        (LeaderboardTable.gameType eq req.gameType)
                            }
                            .firstOrNull()?.get(LeaderboardTable.score) ?: 0

                        LeaderboardTable.selectAll()
                            .where {
                                (LeaderboardTable.gameType eq req.gameType) and
                                        (LeaderboardTable.score greater myScore)
                            }
                            .count().toInt() + 1
                    }

                    call.respond(SubmitScoreResponse(success = true, rank = rank))
                }
            }

            // GET /rank/friends — 好友排行榜（需要 JWT）
            authenticate("auth-jwt") {
                get("/friends") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val gameType = call.request.queryParameters["gameType"] ?: "runner"

                    val entries = transaction {
                        // 查出所有已接受的好友 ID
                        val friendIds = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq userId) and (FriendsTable.status eq "accepted")
                            }
                            .map { it[FriendsTable.friendId] }

                        // 好友 + 自己
                        val allIds = friendIds + userId

                        // 从排行榜取这些用户的分数
                        val rows = LeaderboardTable.selectAll()
                            .where {
                                (LeaderboardTable.gameType eq gameType) and
                                        (LeaderboardTable.userId inList allIds)
                            }
                            .orderBy(LeaderboardTable.score, SortOrder.DESC)
                            .toList()

                        rows.mapIndexed { index, row ->
                            LeaderboardEntry(
                                rank = index + 1,
                                userId = row[LeaderboardTable.userId],
                                nickname = row[LeaderboardTable.nickname],
                                score = row[LeaderboardTable.score],
                                createdAt = row[LeaderboardTable.createdAt]
                            )
                        }
                    }

                    val myEntry = entries.find { it.userId == userId }
                    call.respond(LeaderboardResponse(entries = entries, myEntry = myEntry))
                }
            }

            // GET /rank/list — 查询排行榜（公开，可选 JWT 返回 myEntry）
            get("/list") {
                val gameType = call.request.queryParameters["gameType"] ?: "runner"
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 100)

                val entries = transaction {
                    LeaderboardTable.selectAll()
                        .where { LeaderboardTable.gameType eq gameType }
                        .orderBy(LeaderboardTable.score, SortOrder.DESC)
                        .limit(limit)
                        .mapIndexed { index, row ->
                            LeaderboardEntry(
                                rank = index + 1,
                                userId = row[LeaderboardTable.userId],
                                nickname = row[LeaderboardTable.nickname],
                                score = row[LeaderboardTable.score],
                                createdAt = row[LeaderboardTable.createdAt]
                            )
                        }
                }

                // 尝试从 Authorization header 解析用户（可选）
                var myEntry: LeaderboardEntry? = null
                val authHeader = call.request.headers["Authorization"]
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    try {
                        val token = authHeader.removePrefix("Bearer ")
                        val secret = application.environment.config.property("jwt.secret").getString()
                        val issuer = application.environment.config.property("jwt.issuer").getString()
                        val audience = application.environment.config.property("jwt.audience").getString()
                        val verifier = com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256(secret))
                            .withAudience(audience)
                            .withIssuer(issuer)
                            .build()
                        val decoded = verifier.verify(token)
                        val userId = decoded.getClaim("userId").asInt()

                        // 先查 entries 中是否已有该用户
                        myEntry = entries.find { it.userId == userId }

                        // 若不在 Top N，单独查询
                        if (myEntry == null) {
                            myEntry = transaction {
                                val row = LeaderboardTable.selectAll()
                                    .where {
                                        (LeaderboardTable.userId eq userId) and
                                                (LeaderboardTable.gameType eq gameType)
                                    }
                                    .firstOrNull()
                                if (row != null) {
                                    val myScore = row[LeaderboardTable.score]
                                    val rank = LeaderboardTable.selectAll()
                                        .where {
                                            (LeaderboardTable.gameType eq gameType) and
                                                    (LeaderboardTable.score greater myScore)
                                        }
                                        .count().toInt() + 1
                                    LeaderboardEntry(
                                        rank = rank,
                                        userId = userId,
                                        nickname = row[LeaderboardTable.nickname],
                                        score = myScore,
                                        createdAt = row[LeaderboardTable.createdAt]
                                    )
                                } else null
                            }
                        }
                    } catch (_: Exception) {
                        // token 无效，忽略
                    }
                }

                call.respond(LeaderboardResponse(entries = entries, myEntry = myEntry))
            }
        }
    }
}
