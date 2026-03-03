package com.lightningkite.lightningserver.notifications

import com.lightningkite.EmailAddress
import com.lightningkite.PhoneNumber
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.FrequencyCustomizableSubscriptions
import com.lightningkite.lightningserver.notifications.subscriptions.subscribed
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.postCreate
import com.lightningkite.services.email.Email
import com.lightningkite.services.notifications.NotificationData
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.uuid.Uuid

private object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val notifications = path.path("notifications") module Notifications
}

@Serializable
private data class User(override val _id: Uuid) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = User.serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)
    }
}

private inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.testModelInfo() = modelInfo(
    User.require(),
    permissions = { ModelPermissions.allowAll<T>() }
)



private object Notifications : NotificationEndpoints<
        User, Uuid, String,
        FrequencyCustomizableSubscriptions<User, Uuid>,
        NotificationBulkDispatcher<User, Uuid, String>
>() {
    override val users: ModelInfo<*, User, Uuid> = Server.database.testModelInfo()

    override val dispatcher = path include object : NotificationBulkDispatcher<User, Uuid, String>(
        info = Server.database.testModelInfo(),
        cache = Server.cache,
        database = Server.database,
        users = users,
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
        ): List<String> = emptyList()

        context(runtime: ServerRuntime)
        override suspend fun makePushNotifications(
            user: User,
            notifications: List<Notification<Uuid, String>>
        ): List<NotificationData> = emptyList()
    }

    override val subscriptions: FrequencyCustomizableSubscriptions<User, Uuid> =
        path.path("subscriptions") module FrequencyCustomizableSubscriptions(
            info = Server.database.testModelInfo()
        )
}


@Serializable
@GenerateDataClassPaths
data class Model(
    override val _id: Uuid,
    val name: String
) : HasId<Uuid>

private object ModelEndpoints : ServerBuilder() {
    val info = Server.database.modelInfo<User, Model, Uuid>(
        auth = User.require(),
        permissions = { ModelPermissions.allowAll() },
        signals = {
            it.postCreate { postCreate(it) }
        }
    )

    context(_: ServerRuntime)
    private suspend fun postCreate(model: Model) {
        somethingCreated(model)
    }



    val somethingCreated = Notifications.event("Model Created", info) {
        it.content { event ->
            { user ->
                "Hello $user, ${event.subject.name} was created"
            }
        }

        it.subscribed(
            defaultEmail = null
        ) {
            setOf(Uuid.random())
        }
    }
}

class SyntaxTest {
}