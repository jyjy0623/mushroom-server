package com.mushroom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.event.Level

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureCallLogging()
    configureSerialization()
    configureDatabases()
    configureAuth()
    configureRouting()
    configureAuthRoutes()
    configureLeaderboardRoutes()
    configureFriendRoutes()
}

fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            val requestId = call.request.header("X-Request-ID") ?: "-"
            "$method $path -> $status (${duration}ms) [reqId=$requestId]"
        }
        mdc("requestId") { call ->
            call.request.header("X-Request-ID")
                ?: java.util.UUID.randomUUID().toString().take(8)
        }
        filter { call -> call.request.path() != "/health" }
    }

    // Echo X-Request-ID back to client for correlation
    install(createApplicationPlugin("RequestIdEcho") {
        onCall { call ->
            val requestId = call.request.header("X-Request-ID")
                ?: java.util.UUID.randomUUID().toString().take(8)
            call.response.header("X-Request-ID", requestId)
        }
    })
}
