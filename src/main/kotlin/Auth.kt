package com.mushroom

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import java.util.*

// --- Request / Response models ---

@Serializable
data class SendCodeRequest(val phone: String)

@Serializable
data class SendCodeResponse(val success: Boolean, val message: String)

@Serializable
data class LoginRequest(val phone: String, val code: String, val deviceId: String, val nickname: String? = null)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserProfile,
    val isNewUser: Boolean = false
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class RefreshResponse(val accessToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class UserProfile(
    val id: Int,
    val phone: String,
    val nickname: String,
    val avatarUrl: String
)

@Serializable
data class UpdateProfileRequest(
    val nickname: String? = null,
    val avatarUrl: String? = null
)

// --- JWT Authentication plugin ---

fun Application.configureAuth() {
    val secret = environment.config.property("jwt.secret").getString()
    val issuer = environment.config.property("jwt.issuer").getString()
    val audience = environment.config.property("jwt.audience").getString()
    val realm = environment.config.property("jwt.realm").getString()

    install(Authentication) {
        jwt("auth-jwt") {
            this.realm = realm
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withAudience(audience)
                    .withIssuer(issuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("userId").asInt() != null) {
                    JWTPrincipal(credential.payload)
                } else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token invalid or expired"))
            }
        }
    }
}

// --- Auth & User routes ---

fun Application.configureAuthRoutes() {
    val secret = environment.config.property("jwt.secret").getString()
    val issuer = environment.config.property("jwt.issuer").getString()
    val audience = environment.config.property("jwt.audience").getString()
    val accessExpireMin = environment.config.property("jwt.accessTokenExpireMinutes").getString().toLong()
    val refreshExpireDays = environment.config.property("jwt.refreshTokenExpireDays").getString().toLong()

    routing {
        route("/auth") {

            // POST /auth/send-code — Mock: always succeeds
            post("/send-code") {
                val req = call.receive<SendCodeRequest>()
                if (!req.phone.matches(Regex("^1[3-9]\\d{9}$"))) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "手机号格式不正确"))
                    return@post
                }
                application.log.info("Mock SMS sent to ${req.phone}, code: 123456")
                call.respond(SendCodeResponse(success = true, message = "验证码已发送"))
            }

            // POST /auth/login
            post("/login") {
                val req = call.receive<LoginRequest>()
                if (req.code != "123456") {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "验证码错误"))
                    return@post
                }

                val (user, isNewUser) = transaction {
                    val existing = UsersTable.selectAll()
                        .where { UsersTable.phone eq req.phone }
                        .firstOrNull()
                    if (existing != null) {
                        Pair(UserProfile(
                            id = existing[UsersTable.id],
                            phone = existing[UsersTable.phone],
                            nickname = existing[UsersTable.nickname],
                            avatarUrl = existing[UsersTable.avatarUrl]
                        ), false)
                    } else {
                        val now = System.currentTimeMillis()
                        val displayName = req.nickname?.takeIf { it.isNotBlank() } ?: "蘑菇冒险家"
                        val id = UsersTable.insert {
                            it[phone] = req.phone
                            it[nickname] = displayName
                            it[avatarUrl] = ""
                            it[createdAt] = now
                            it[updatedAt] = now
                        } get UsersTable.id
                        Pair(UserProfile(id = id, phone = req.phone, nickname = displayName, avatarUrl = ""), true)
                    }
                }

                val accessToken = generateAccessToken(user.id, secret, issuer, audience, accessExpireMin)
                val refreshTokenStr = UUID.randomUUID().toString()
                val refreshExpiry = System.currentTimeMillis() + refreshExpireDays * 24 * 60 * 60 * 1000

                transaction {
                    // 同设备只保留一个 refresh token
                    RefreshTokensTable.deleteWhere {
                        (RefreshTokensTable.userId eq user.id) and
                                (RefreshTokensTable.deviceId eq req.deviceId)
                    }
                    RefreshTokensTable.insert {
                        it[userId] = user.id
                        it[token] = refreshTokenStr
                        it[deviceId] = req.deviceId
                        it[expiresAt] = refreshExpiry
                        it[createdAt] = System.currentTimeMillis()
                    }
                }

                call.respond(LoginResponse(accessToken, refreshTokenStr, user, isNewUser))
            }

            // POST /auth/refresh
            post("/refresh") {
                val req = call.receive<RefreshRequest>()
                val record = transaction {
                    RefreshTokensTable.selectAll()
                        .where { RefreshTokensTable.token eq req.refreshToken }
                        .firstOrNull()
                }
                if (record == null || record[RefreshTokensTable.expiresAt] < System.currentTimeMillis()) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Refresh token invalid or expired"))
                    return@post
                }
                val userId = record[RefreshTokensTable.userId]
                val newAccessToken = generateAccessToken(userId, secret, issuer, audience, accessExpireMin)
                call.respond(RefreshResponse(newAccessToken))
            }

            // POST /auth/logout
            post("/logout") {
                val req = call.receive<LogoutRequest>()
                transaction {
                    RefreshTokensTable.deleteWhere { RefreshTokensTable.token eq req.refreshToken }
                }
                call.respond(mapOf("success" to true))
            }
        }

        // Protected user routes
        authenticate("auth-jwt") {
            route("/user") {

                // GET /user/profile
                get("/profile") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val row = transaction {
                        UsersTable.selectAll().where { UsersTable.id eq userId }.firstOrNull()
                    }
                    if (row == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    } else {
                        call.respond(
                            UserProfile(
                                id = row[UsersTable.id],
                                phone = row[UsersTable.phone],
                                nickname = row[UsersTable.nickname],
                                avatarUrl = row[UsersTable.avatarUrl]
                            )
                        )
                    }
                }

                // PUT /user/profile
                put("/profile") {
                    val userId = call.principal<JWTPrincipal>()!!.payload.getClaim("userId").asInt()
                    val req = call.receive<UpdateProfileRequest>()
                    transaction {
                        UsersTable.update({ UsersTable.id eq userId }) {
                            req.nickname?.let { n -> it[nickname] = n }
                            req.avatarUrl?.let { a -> it[avatarUrl] = a }
                            it[updatedAt] = System.currentTimeMillis()
                        }
                    }
                    val updated = transaction {
                        UsersTable.selectAll().where { UsersTable.id eq userId }.first()
                    }
                    call.respond(
                        UserProfile(
                            id = updated[UsersTable.id],
                            phone = updated[UsersTable.phone],
                            nickname = updated[UsersTable.nickname],
                            avatarUrl = updated[UsersTable.avatarUrl]
                        )
                    )
                }
            }
        }
    }
}

private fun generateAccessToken(
    userId: Int, secret: String, issuer: String, audience: String, expireMinutes: Long
): String = JWT.create()
    .withAudience(audience)
    .withIssuer(issuer)
    .withClaim("userId", userId)
    .withExpiresAt(Date(System.currentTimeMillis() + expireMinutes * 60 * 1000))
    .sign(Algorithm.HMAC256(secret))
