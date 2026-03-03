package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.all
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.postCreate
import com.lightningkite.services.database.postDelete
import com.lightningkite.services.email.Email
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.sms.SMS
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.uuid.Uuid

private object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val sms = setting("sms", SMS.Settings())

    val notifications = path.path("notifications") module Notifications

    val modelEndpoints = path.path("model") module ModelEndpoints
}

@Serializable
private data class User(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)

        val info = Server.database.modelInfo(User.require(), permissions = { ModelPermissions.allowAll<User>() })
    }
}

private inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.testModelInfo() = modelInfo(
    User.require(),
    permissions = { ModelPermissions.allowAll<T>() }
)

private object Notifications : NotificationEndpoints<
    User, Uuid,
    String,
    NotificationBulkDispatcher<User, Uuid, String>,
    FrequencyCustomizableSubscriptions<User, Uuid>,
>(
    users = User.info,
    dispatcher = Dispatcher,
    subscriptions = FrequencyCustomizableSubscriptions(
        info = Server.database.testModelInfo()
    )
) {
    object Dispatcher : NotificationBulkDispatcher<User, Uuid, String>(
        info = Server.database.testModelInfo(),
        cache = Server.cache,
        database = Server.database,
        users = User.info,
        sms = Server.sms,
        contentSerializer = String.serializer(),
    ) {
        context(server: ServerRuntime) override suspend fun email(user: User): EmailAddress? = null
        context(server: ServerRuntime) override suspend fun phone(user: User): PhoneNumber? = null
        context(server: ServerRuntime) override suspend fun fcmTokens(user: User): Set<String> = emptySet()
        context(server: ServerRuntime) override suspend fun onFcmTokensDead(user: User, deadTokens: Set<String>) {}

        context(runtime: ServerRuntime)
        override suspend fun makeEmailNotifications(
            user: User,
            notifications: List<Notification<Uuid, String>>
        ): List<Email> = emptyList()

        context(runtime: ServerRuntime)
        override suspend fun makeSmsNotifications(
            user: User,
            notifications: List<Notification<Uuid, String>>
        ): List<String> = notifications.map { it.content }

        context(runtime: ServerRuntime)
        override suspend fun makePushNotifications(
            user: User,
            notifications: List<Notification<Uuid, String>>
        ): List<NotificationData> = emptyList()
    }
}

@Serializable
@GenerateDataClassPaths
data class Model(
    override val _id: Uuid = Uuid.random(),
    val name: String = "Hello World"
) : HasId<Uuid>


private object ModelEndpoints : ServerBuilder() {
    val info: ModelInfo<User, Model, Uuid> = Server.database.modelInfo(
        auth = User.require(),
        permissions = { ModelPermissions.allowAll() },
        signals = { table ->
            table
                .postCreate { somethingCreated(it) }
                .postDelete { somethingDeleted(it) }
        }
    )

    val somethingCreated = Notifications.event("Model Created", info) { notif ->
        notif.subscribed {
            users.table().all().map { it._id }.toSet()
        }
        notif.content { event ->
            { user ->
                "Hello, $user. ${event.subject} was just created."
            }
        }
    }

    val somethingDeleted = Notifications.event("Model Deleted", info) { notif ->
        notif.subscribed {
            users.table().all().map { it._id }.toSet()
        }
        notif.content { event ->
            { user ->
                "Hello, $user. ${event.subject} was destroyed."
            }
        }
    }
}

class SyntaxTest {
    @Test
    fun testCompiles() {
        Server.test({}) {
            runBlocking {
                modelEndpoints.info.table().insertOne(
                    Model()
                )
            }
        }
    }
}