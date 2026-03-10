package com.mushroom

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

// ping 表：用于验证数据库读写
object PingTable : Table("ping") {
    val id = integer("id").autoIncrement()
    val message = varchar("message", 255)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

fun Application.configureDatabases(testDb: Database? = null) {
    val database = testDb ?: run {
        val url = environment.config.property("postgres.url").getString()
        val user = environment.config.property("postgres.user").getString()
        val password = environment.config.property("postgres.password").getString()
        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        ).also { log.info("Database connected: $url") }
    }

    // 启动时自动建表
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(PingTable)
    }
}
