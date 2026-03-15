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
data class AddFriendRequest(val phone: String, val message: String = "")

@Serializable
data class AddFriendResponse(val success: Boolean, val message: String)

@Serializable
data class FriendInfo(val userId: Int, val nickname: String, val maskedPhone: String)

@Serializable
data class FriendListResponse(val friends: List<FriendInfo>)

@Serializable
data class FriendRequestInfo(
    val id: Int,
    val fromUserId: Int,
    val nickname: String,
    val maskedPhone: String,
    val message: String,
    val createdAt: Long
)

@Serializable
data class FriendRequestListResponse(
    val requests: List<FriendRequestInfo>,
    val total: Int
)

@Serializable
data class HandleRequestBody(val action: String) // "accept" / "reject"

@Serializable
data class PendingCountResponse(val count: Int)

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

                // POST /friend/add — 发送好友申请（带留言）
                post("/add") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val req = call.receive<AddFriendRequest>()
                    val phone = req.phone.trim()
                    val reqMessage = req.message.trim().take(128)

                    if (!phone.matches(Regex("^1[3-9]\\d{9}$"))) {
                        call.respond(HttpStatusCode.BadRequest, AddFriendResponse(false, "手机号格式不正确"))
                        return@post
                    }

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
                        // 检查 A->B 是否已存在
                        val existing = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq userId) and (FriendsTable.friendId eq targetId)
                            }
                            .firstOrNull()

                        if (existing != null) {
                            return@transaction when (existing[FriendsTable.status]) {
                                "accepted" -> "already_friend"
                                "pending" -> "already_pending"
                                else -> "already_friend"
                            }
                        }

                        // 检查 B->A 是否有 pending（对方先申请了我）
                        val reverseRequest = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.userId eq targetId) and
                                        (FriendsTable.friendId eq userId) and
                                        (FriendsTable.status eq "pending")
                            }
                            .firstOrNull()

                        if (reverseRequest != null) {
                            // 对方已申请过我 -> 直接双向 accepted
                            FriendsTable.update({ FriendsTable.id eq reverseRequest[FriendsTable.id] }) {
                                it[status] = "accepted"
                            }
                            FriendsTable.insert {
                                it[FriendsTable.userId] = userId
                                it[friendId] = targetId
                                it[status] = "accepted"
                                it[message] = reqMessage
                                it[createdAt] = now
                            }
                            return@transaction "auto_accepted"
                        }

                        // 常规：插入 pending 申请
                        FriendsTable.insert {
                            it[FriendsTable.userId] = userId
                            it[friendId] = targetId
                            it[status] = "pending"
                            it[message] = reqMessage
                            it[createdAt] = now
                        }
                        "pending_sent"
                    }

                    val (success, msg) = when (result) {
                        "already_friend" -> false to "已经是好友了"
                        "already_pending" -> false to "已发送过申请，等待对方同意"
                        "auto_accepted" -> true to "对方也向你发送了申请，已自动添加为好友！"
                        "pending_sent" -> true to "好友申请已发送，等待对方同意"
                        else -> false to "操作完成"
                    }
                    call.respond(AddFriendResponse(success = success, message = msg))
                }

                // GET /friend/requests — 获取收到的待处理好友申请
                get("/requests") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()

                    val requests = transaction {
                        val pendingRows = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.friendId eq userId) and (FriendsTable.status eq "pending")
                            }
                            .orderBy(FriendsTable.createdAt, SortOrder.DESC)
                            .toList()

                        val fromUserIds = pendingRows.map { it[FriendsTable.userId] }
                        val usersMap = if (fromUserIds.isNotEmpty()) {
                            UsersTable.selectAll()
                                .where { UsersTable.id inList fromUserIds }
                                .associate { it[UsersTable.id] to Pair(it[UsersTable.nickname], it[UsersTable.phone]) }
                        } else emptyMap()

                        pendingRows.map { row ->
                            val fromId = row[FriendsTable.userId]
                            val (nickname, phoneVal) = usersMap[fromId] ?: ("" to "")
                            FriendRequestInfo(
                                id = row[FriendsTable.id],
                                fromUserId = fromId,
                                nickname = nickname,
                                maskedPhone = maskPhone(phoneVal),
                                message = row[FriendsTable.message],
                                createdAt = row[FriendsTable.createdAt]
                            )
                        }
                    }

                    call.respond(FriendRequestListResponse(requests = requests, total = requests.size))
                }

                // GET /friend/requests/count — 获取待处理申请数量
                get("/requests/count") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val count = transaction {
                        FriendsTable.selectAll()
                            .where {
                                (FriendsTable.friendId eq userId) and (FriendsTable.status eq "pending")
                            }
                            .count().toInt()
                    }
                    call.respond(PendingCountResponse(count = count))
                }

                // POST /friend/requests/{id}/handle — 同意或拒绝好友申请
                post("/requests/{id}/handle") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val requestId = call.parameters["id"]?.toIntOrNull()
                    if (requestId == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "无效的请求 ID"))
                        return@post
                    }

                    val body = call.receive<HandleRequestBody>()
                    if (body.action !in listOf("accept", "reject")) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "action 必须是 accept 或 reject"))
                        return@post
                    }

                    val now = System.currentTimeMillis()

                    val result = transaction {
                        val request = FriendsTable.selectAll()
                            .where {
                                (FriendsTable.id eq requestId) and
                                        (FriendsTable.friendId eq userId) and
                                        (FriendsTable.status eq "pending")
                            }
                            .firstOrNull()
                            ?: return@transaction "not_found"

                        val fromUserId = request[FriendsTable.userId]

                        if (body.action == "accept") {
                            // 将 pending 改为 accepted
                            FriendsTable.update({ FriendsTable.id eq requestId }) {
                                it[status] = "accepted"
                            }
                            // 插入反向 accepted 记录
                            val reverseExists = FriendsTable.selectAll()
                                .where {
                                    (FriendsTable.userId eq userId) and (FriendsTable.friendId eq fromUserId)
                                }
                                .firstOrNull()
                            if (reverseExists == null) {
                                FriendsTable.insert {
                                    it[FriendsTable.userId] = userId
                                    it[friendId] = fromUserId
                                    it[FriendsTable.status] = "accepted"
                                    it[message] = ""
                                    it[createdAt] = now
                                }
                            } else {
                                FriendsTable.update({
                                    (FriendsTable.userId eq userId) and (FriendsTable.friendId eq fromUserId)
                                }) {
                                    it[status] = "accepted"
                                }
                            }
                            "accepted"
                        } else {
                            // reject: 删除该 pending 记录
                            FriendsTable.deleteWhere { FriendsTable.id eq requestId }
                            "rejected"
                        }
                    }

                    when (result) {
                        "not_found" -> call.respond(HttpStatusCode.NotFound, AddFriendResponse(false, "申请不存在或已处理"))
                        "accepted" -> call.respond(AddFriendResponse(true, "已同意好友申请"))
                        "rejected" -> call.respond(AddFriendResponse(true, "已拒绝好友申请"))
                    }
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

                    call.respond(AddFriendResponse(true, "已删除好友"))
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

                    val nickname = transaction {
                        UsersTable.selectAll()
                            .where { UsersTable.id eq targetId }
                            .firstOrNull()?.get(UsersTable.nickname) ?: ""
                    }

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
