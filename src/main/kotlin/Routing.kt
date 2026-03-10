package com.mushroom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class PingRequest(val message: String)

@Serializable
data class PingRecord(val id: Int, val message: String, val createdAt: Long)

@Serializable
data class HealthResponse(val status: String, val version: String = "0.0.1")

fun Application.configureRouting() {
    routing {

        // 健康检查
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        // 写一条记录到数据库
        post("/ping") {
            val req = call.receive<PingRequest>()
            val id = transaction {
                PingTable.insert {
                    it[message] = req.message
                    it[createdAt] = System.currentTimeMillis()
                } get PingTable.id
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to id))
        }

        // 读最近 10 条记录
        get("/ping") {
            val records = transaction {
                PingTable.selectAll()
                    .orderBy(PingTable.id, SortOrder.DESC)
                    .limit(10)
                    .map {
                        PingRecord(
                            id = it[PingTable.id],
                            message = it[PingTable.message],
                            createdAt = it[PingTable.createdAt]
                        )
                    }
            }
            call.respond(records)
        }
    }
}
