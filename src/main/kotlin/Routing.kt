package com.mushroom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class PingRequest(val message: String)

@Serializable
data class PingRecord(val id: Int, val message: String, val createdAt: Long)

@Serializable
data class HealthResponse(val status: String, val version: String = "0.0.1")

// --- Cloud Backup models ---

@Serializable
data class CloudBackupUploadRequest(
    val deviceId: String,
    val backup: JsonObject
)

@Serializable
data class CloudBackupUploadResponse(
    val id: Int,
    val exportedAt: String,
    val createdAt: Long
)

@Serializable
data class CloudBackupSummary(
    val id: Int,
    val exportedAt: String,
    val taskCount: Int,
    val sizeBytes: Int,
    val createdAt: Long
)

@Serializable
data class CloudBackupDownloadResponse(
    val id: Int,
    val deviceId: String,
    val backup: JsonObject,
    val exportedAt: String,
    val createdAt: Long
)

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

        // --- Cloud Backup API ---
        route("/backup") {

            // 上传备份
            post("/upload") {
                val req = call.receive<CloudBackupUploadRequest>()
                val backupJsonStr = req.backup.toString()
                val taskCount = req.backup["tasks"]?.jsonArray?.size ?: 0
                val exportedAt = req.backup["exportedAt"]?.jsonPrimitive?.content ?: ""
                val sizeBytes = backupJsonStr.length

                // 每个设备最多保留 5 个备份，超出删除最旧的
                transaction {
                    val existingCount = CloudBackupTable
                        .selectAll()
                        .where { CloudBackupTable.deviceId eq req.deviceId }
                        .count()
                    if (existingCount >= 5) {
                        val oldestIds = CloudBackupTable
                            .select(CloudBackupTable.id)
                            .where { CloudBackupTable.deviceId eq req.deviceId }
                            .orderBy(CloudBackupTable.createdAt, SortOrder.ASC)
                            .limit((existingCount - 4).toInt())
                            .map { it[CloudBackupTable.id] }
                        CloudBackupTable.deleteWhere { CloudBackupTable.id inList oldestIds }
                    }
                }

                val now = System.currentTimeMillis()
                val id = transaction {
                    CloudBackupTable.insert {
                        it[CloudBackupTable.deviceId] = req.deviceId
                        it[CloudBackupTable.backupJson] = backupJsonStr
                        it[CloudBackupTable.exportedAt] = exportedAt
                        it[CloudBackupTable.taskCount] = taskCount
                        it[CloudBackupTable.sizeBytes] = sizeBytes
                        it[CloudBackupTable.createdAt] = now
                    } get CloudBackupTable.id
                }

                call.respond(HttpStatusCode.Created, CloudBackupUploadResponse(id, exportedAt, now))
            }

            // 列出设备的备份摘要
            get("/list") {
                val deviceId = call.request.queryParameters["deviceId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "deviceId required")
                    )
                val list = transaction {
                    CloudBackupTable
                        .select(
                            CloudBackupTable.id,
                            CloudBackupTable.exportedAt,
                            CloudBackupTable.taskCount,
                            CloudBackupTable.sizeBytes,
                            CloudBackupTable.createdAt
                        )
                        .where { CloudBackupTable.deviceId eq deviceId }
                        .orderBy(CloudBackupTable.createdAt, SortOrder.DESC)
                        .map {
                            CloudBackupSummary(
                                id = it[CloudBackupTable.id],
                                exportedAt = it[CloudBackupTable.exportedAt],
                                taskCount = it[CloudBackupTable.taskCount],
                                sizeBytes = it[CloudBackupTable.sizeBytes],
                                createdAt = it[CloudBackupTable.createdAt]
                            )
                        }
                }
                call.respond(list)
            }

            // 下载完整备份
            get("/{id}/download") {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "invalid id")
                    )
                val record = transaction {
                    CloudBackupTable.selectAll()
                        .where { CloudBackupTable.id eq id }
                        .firstOrNull()
                }
                if (record == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "backup not found"))
                } else {
                    val backupJson = Json.parseToJsonElement(
                        record[CloudBackupTable.backupJson]
                    ).jsonObject
                    call.respond(
                        CloudBackupDownloadResponse(
                            id = record[CloudBackupTable.id],
                            deviceId = record[CloudBackupTable.deviceId],
                            backup = backupJson,
                            exportedAt = record[CloudBackupTable.exportedAt],
                            createdAt = record[CloudBackupTable.createdAt]
                        )
                    )
                }
            }

            // 删除备份
            delete("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "invalid id")
                    )
                val deleted = transaction {
                    CloudBackupTable.deleteWhere { CloudBackupTable.id eq id }
                }
                if (deleted > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "backup not found"))
                }
            }
        }
    }
}
