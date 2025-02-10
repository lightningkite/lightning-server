package com.lightningkite.lightningserver.monitoring

import com.lightningkite.lightningdb.*
import com.lightningkite.UUID
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authOptions
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.token.JwtTokenFormat
import com.lightningkite.lightningserver.auth.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.client
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.*
import com.lightningkite.lightningserver.encryption.encryptor
import com.lightningkite.lightningserver.encryption.hasher
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.ServerHealth
import com.lightningkite.lightningserver.tasks.Tasks
import com.lightningkite.lightningserver.tasks.doOnce
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.arg
import com.lightningkite.lightningserver.typed.path1
import com.lightningkite.lightningserver.typed.post
import com.lightningkite.now
import com.lightningkite.nowLocal
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.decodeBase64Bytes
import io.ktor.util.decodeBase64String
import io.ktor.util.encodeBase64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.serializer
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class UserEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = Server.database.modelInfo<User, User, UUID>(
        authOptions = authOptions<User>(),
        permissions = {
            ModelPermissions(
                create = Condition.Always,
                read = Condition.Always,
                update = Condition.Always,
                delete = Condition.Always,
            )
        }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)
}

class ApplicationEndpoints(path: ServerPath): ServerPathGroup(path) {
    val signer = secretBasis.hasher("application")
    val info = Server.database.modelInfo<User, Application, String>(
        authOptions = authOptions(),
        permissions = {
            ModelPermissions(
                create = Condition.Always,
                read = Condition.Always,
                readMask = mask {
                    it.token.mask("CENSORED")
                },
                update = Condition.Always,
                delete = Condition.Always,
            )
        },
        signals = {
            it.interceptCreate {
                it.copy(reportToken = signer().sign(it._id.toByteArray()).encodeBase64())
            }
        }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)
    fun verify(application: String, token: String) = signer().verify(application.toByteArray(), token.decodeBase64Bytes())

    init {
        Tasks.onEngineReady {
            doOnce("Add token", Server.database) {
                info.collection().all().collect {
                    info.collection().updateOneById(it._id, modification { m ->
                        m.reportToken assign signer().sign(it._id.toByteArray()).encodeBase64()
                    })
                }
            }
        }
    }
}


class ApplicationHealthCheckEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = Server.database.modelInfo<User, ApplicationHealthCheck, UUID>(
        authOptions = authOptions(),
        permissions = { ModelPermissions.allowAll() },
        signals = { nosig ->
            nosig.interceptCreate { new ->
                val last = nosig.find(condition {
                    it.application eq new.application
                }, orderBy = sort { it.at.descending() }, limit = 1).firstOrNull()
                val lastOverall = last?.result?.overall
                run {
                    if (lastOverall != new.result?.overall) {
                        val application = Server.application.info.collection().get(new.application) ?: return@run
                        application.slackChannel?.let {
                            val mentions = application.slackUsers.joinToString(separator = " ") { "<@$it>" }
                            slack(it, "$mentions Server health status changed to ${new.result?.overall}")
                        }
                    }
                }
                new
            }
        }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)

    val automaticallyCreate = schedule("$path/automaticallyCreate", 1.minutes) {
        val epochMinute = (now().toEpochMilliseconds() / 1000 / 60).toInt()
        Server.application.info.collection().all().collect {
            if(epochMinute % it.checkFrequencyMinutes == 0) {
                val response = client.get("https://${it._id}/meta/health") {
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Application.Json)
                    it.token?.let {
                        header(HttpHeaders.Authorization, "Bearer ${it}")
                    }
                }
                info.collection().insertOne(ApplicationHealthCheck(
                    application = it._id,
                    result = if(response.status.isSuccess())
                        Serialization.json.decodeFromString(ServerHealth.serializer(), response.bodyAsText())
                    else null,
                    statusCode = response.status.value
                ))
            }
        }
    }
}


class ApplicationStackTraceEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = Server.database.modelInfo<User, ApplicationStackTrace, UUID>(
        authOptions = authOptions(),
        permissions = { ModelPermissions.allowAll() }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)
}


class FunnelEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = Server.database.modelInfo<User, Funnel, String>(
        authOptions = authOptions(),
        permissions = { ModelPermissions.allowAll() }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)
}


class FunnelInstanceEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = Server.database.modelInfo<User, FunnelInstance, UUID>(
        authOptions = authOptions(),
        permissions = { ModelPermissions.allowAll() }
    )

    val rest = ModelRestEndpoints(path("rest"), info)
    val restWebsocket = ModelRestUpdatesWebsocket(path("rest"), info)
    init { path.docName = rest.path.docName }

    val start = path("start").post.api(
        authOptions = noAuth,
        summary = "Start Funnel Instance"
    ) { input: FunnelStart ->
        if(!Server.application.verify(input.funnel.substringBefore("/"), input.token)) throw ForbiddenException()
        info.collection().insertOne(FunnelInstance(
            funnel = input.funnel,
            userAgent = input.userAgent,
            version = input.version,
            expiry = now().plus(input.expireAfterMinutes.minutes)
        ))!!._id
    }

    val error = path("error").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Error Funnel Instance"
    ) { input: String ->
        info.collection().updateOneById(path1, modification {
            it.errors += input
        })
        Unit
    }
    val step = path("step").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Set Step Funnel Instance"
    ) { step: Int ->
        info.collection().updateOneById(path1, modification {
            it.step.coerceAtLeast(step)
        })
        Unit
    }
    val success = path("success").arg<UUID>("id").post.api(
        authOptions = noAuth,
        summary = "Success Funnel Instance"
    ) { _: Unit ->
        info.collection().updateOneById(path1, modification {
            it.success assign now()
        })
        Unit
    }
}
