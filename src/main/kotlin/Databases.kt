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

// 用户表
object UsersTable : Table("users") {
    val id = integer("id").autoIncrement()
    val phone = varchar("phone", 20).uniqueIndex()
    val nickname = varchar("nickname", 32).default("")
    val avatarUrl = varchar("avatar_url", 512).default("")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// 刷新令牌表
object RefreshTokensTable : Table("refresh_tokens") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id)
    val token = varchar("token", 512).uniqueIndex()
    val deviceId = varchar("device_id", 128)
    val expiresAt = long("expires_at")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

// 云端备份表：存储客户端上传的 BackupPayload JSON
object CloudBackupTable : Table("cloud_backups") {
    val id = integer("id").autoIncrement()
    val deviceId = varchar("device_id", 128)
    val backupJson = text("backup_json")
    val exportedAt = varchar("exported_at", 64)
    val taskCount = integer("task_count")
    val sizeBytes = integer("size_bytes")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

// 排行榜表：每用户每游戏类型只保留最高分
object LeaderboardTable : Table("leaderboard") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id)
    val nickname = varchar("nickname", 32)
    val gameType = varchar("game_type", 32)
    val score = integer("score")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// 好友关系表
object FriendsTable : Table("friends") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id)
    val friendId = integer("friend_id").references(UsersTable.id)
    val status = varchar("status", 16) // "pending" / "accepted"
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, friendId)
    }
}

// 用户统计表：客户端同步的学习/蘑菇汇总数据
object UserStatsTable : Table("user_stats") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(UsersTable.id).uniqueIndex()
    val currentStreak = integer("current_streak").default(0)
    val longestStreak = integer("longest_streak").default(0)
    val totalCheckins = integer("total_checkins").default(0)
    val totalMushroomPoints = integer("total_mushroom_points").default(0)
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

fun Application.configureDatabases(testDb: Database? = null) {
    val database = testDb ?: run {
        // 优先读取环境变量，其次读取配置文件
        val url = System.getenv("POSTGRES_URL") ?: environment.config.property("postgres.url").getString()
        val user = System.getenv("POSTGRES_USER") ?: environment.config.property("postgres.user").getString()
        val password = System.getenv("POSTGRES_PASSWORD") ?: environment.config.property("postgres.password").getString()

        log.info("Database config - URL: $url, User: $user")
        log.info("Env POSTGRES_URL: ${System.getenv("POSTGRES_URL")}")

        Database.connect(
            url = url,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        ).also { log.info("Database connected: $url") }
    }

    // 启动时自动建表
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(PingTable, UsersTable, RefreshTokensTable, CloudBackupTable, LeaderboardTable, FriendsTable, UserStatsTable)
    }
}
