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

// --- Data models ---

@Serializable
data class AddFriendRequest(val phone: String)

@Serializable
data class AddFriendResponse(val success: Boolean, val message: String)

@Serializable
data class FriendInfo(val userId: Int, val nickname: String, val maskedPhone: String)

@Serializable
data class FriendListResponse(val friends: List<FriendInfo>)

@Serializable
data class FriendStatsResponse(
    val userId: Int,
    val nickname: String,
    val bestScore: Int? = null,
    val globalRank: Int? = null,
    val gameType: String,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalCheckins: Int = 0,
    val totalMushroomPoints: Int = 0
)

// --- Helper ---

private fun maskPhone(phone: String): String {
    return if (phone.length == 11) {
        phone.substring(0, 3) + "****" + phone.substring(7)
    } else phone
}

// --- Routes ---

fun Application.configureFriendRoutes() {
    routing {
        authenticate("auth-jwt") {
            route("/friend") {

                // POST /friend/add — 通过手机号添加好友
                post("/add") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val req = call.receive<AddFriendRequest>()
                    val phone = req.phone.trim()

                    if (!phone.matches(Regex("^1[3-9]\\d{9}$"))) {
                        call.respond(HttpStatusCode.BadRequest, AddFriendResponse(false, "手机号格式不正确"))
                        return@post
                    }

                    // 查找目标用户
                    val targetUser = transaction {
                        UsersTable.selectAll()
                            .where { UsersTable.phone eq phone }
                            .firstOrNull()
                    }
                    if (targetUser == null) {
                        call.respond(HttpStatusCode.NotFound, AddFriendResponse(false, "未找到该手机号对应的用户"))
                        return@post
                    }

                    val targetId = targetUser[UsersTable.id]
                    if (targetId == userId) {
                        call.respond(HttpStatusCode.BadRequest, AddFriendResponse(false, "不能添加自己为好友"))
                        return@post
                    }

                    val now = System.currentTimeMillis()

                    val result = transaction {
                        // 检查是否已存在好友关系
                        val existing = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq userId) and (FriendsTable.friendId eq targetId)
                            }
                            .firstOrNull()

                        if (existing != null) {
                            return@transaction "already"
                        }

                        // 直接添加为好友（双向 accepted）
                        FriendsTable.insert {
                            it[FriendsTable.userId] = userId
                            it[friendId] = targetId
                            it[status] = "accepted"
                            it[createdAt] = now
                        }

                        // 检查对方是否也已添加我
                        val reverse = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq targetId) and (FriendsTable.friendId eq userId)
                            }
                            .firstOrNull()

                        if (reverse == null) {
                            FriendsTable.insert {
                                it[FriendsTable.userId] = targetId
                                it[friendId] = userId
                                it[status] = "accepted"
                                it[createdAt] = now
                            }
                        }

                        "added"
                    }

                    val message = when (result) {
                        "already" -> "已经是好友了"
                        "added" -> "好友添加成功！"
                        else -> "操作完成"
                    }
                    call.respond(AddFriendResponse(success = result == "added", message = message))
                }

                // GET /friend/list — 获取好友列表
                get("/list") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()

                    val friends = transaction {
                        val acceptedIds = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq userId) and (FriendsTable.status eq "accepted")
                            }
                            .map { it[FriendsTable.friendId] }

                        if (acceptedIds.isNotEmpty()) {
                            UsersTable.selectAll()
                                .where { UsersTable.id inList acceptedIds }
                                .map {
                                    FriendInfo(
                                        userId = it[UsersTable.id],
                                        nickname = it[UsersTable.nickname],
                                        maskedPhone = maskPhone(it[UsersTable.phone])
                                    )
                                }
                        } else emptyList()
                    }

                    call.respond(FriendListResponse(friends = friends))
                }

                // DELETE /friend/{userId} — 删除好友
                delete("/{targetUserId}") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val targetId = call.parameters["targetUserId"]?.toIntOrNull()
                    if (targetId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "无效的用户 ID"))
                        return@delete
                    }

                    transaction {
                        FriendsTable.deleteWhere {
                            ((FriendsTable.userId eq userId) and (FriendsTable.friendId eq targetId)) or
                                    ((FriendsTable.userId eq targetId) and (FriendsTable.friendId eq userId))
                        }
                    }

                    call.respond(mapOf("success" to true))
                }

                // GET /friend/{targetUserId}/stats — 查看好友数据统计
                get("/{targetUserId}/stats") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val targetId = call.parameters["targetUserId"]?.toIntOrNull()
                    if (targetId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "无效的用户 ID"))
                        return@get
                    }

                    val gameType = call.request.queryParameters["gameType"] ?: "runner"

                    // 安全校验：验证是好友关系
                    val isFriend = transaction {
                        FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq userId) and
                                        (FriendsTable.friendId eq targetId) and
                                        (FriendsTable.status eq "accepted")
                            }
                            .count() > 0
                    }
                    if (!isFriend) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "非好友关系"))
                        return@get
                    }

                    // 获取好友昵称
                    val nickname = transaction {
                        UsersTable.selectAll()
                            .where { UsersTable.id eq targetId }
                            .firstOrNull()?.get(UsersTable.nickname) ?: ""
                    }

                    // 查询最高分
                    val scoreRow = transaction {
                        LeaderboardTable.selectAll()
                            .where {
                                (LeaderboardTable.userId eq targetId) and
                                        (LeaderboardTable.gameType eq gameType)
                            }
                            .firstOrNull()
                    }

                    val bestScore = scoreRow?.get(LeaderboardTable.score)
                    val globalRank = if (bestScore != null) {
                        transaction {
                            LeaderboardTable.selectAll()
                                .where {
                                    (LeaderboardTable.gameType eq gameType) and
                                            (LeaderboardTable.score greater bestScore)
                                }
                                .count().toInt() + 1
                        }
                    } else null

                    // 查询学习/蘑菇统计
                    val userStats = transaction {
                        UserStatsTable.selectAll()
                            .where { UserStatsTable.userId eq targetId }
                            .firstOrNull()
                    }

                    call.respond(
                        FriendStatsResponse(
                            userId = targetId,
                            nickname = nickname,
                            bestScore = bestScore,
                            globalRank = globalRank,
                            gameType = gameType,
                            currentStreak = userStats?.get(UserStatsTable.currentStreak) ?: 0,
                            longestStreak = userStats?.get(UserStatsTable.longestStreak) ?: 0,
                            totalCheckins = userStats?.get(UserStatsTable.totalCheckins) ?: 0,
                            totalMushroomPoints = userStats?.get(UserStatsTable.totalMushroomPoints) ?: 0
                        )
                    )
                }
            }
        }
    }
}
