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
            }
        }
    }
}
