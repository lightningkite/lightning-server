// by Claude
package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.sms.SMS
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.time.*
import kotlin.uuid.Uuid

/**
 * Test user model for notification tests.
 */
@Serializable
data class TestUser(
    override val _id: Uuid = Uuid.random(),
    val name: String = "Test User",
    val email: String = "test@example.com",
    val phone: String = "1234567890",
) : HasId<Uuid> {
    companion object : PrincipalType<TestUser, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<TestUser> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
    }
}

/**
 * Test model entity for triggering notification events.
 */
@Serializable
data class TestModel(
    override val _id: Uuid = Uuid.random(),
    val name: String = "Test Model",
    val ownerId: Uuid = Uuid.random(),
) : HasId<Uuid>

/**
 * Extension function to create a test ModelInfo with full permissions.
 */
context(builder: ServerBuilder)
inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.testModelInfo(): ModelInfo<TestUser, T, ID> =
    modelInfo(
        TestUser.require(),
        tableName = T::class.simpleName!!,
        permissions = { ModelPermissions.allowAll() }
    )

/**
 * Mutable clock for time-travel testing.
 * Advance time by modifying [measuredFrom].
 */
class TestClock : Clock {
    var measuredFrom: Instant = Clock.System.now()
    var mark = TimeSource.Monotonic.markNow()

    override fun now(): Instant = measuredFrom + mark.elapsedNow()
}

/**
 * Creates a standard test dispatcher for String content type.
 */
abstract class TestDispatcherBase(
    info: ModelInfo<*, Notification<Uuid, String>, Uuid>,
    cache: Runtime<Cache>,
    database: Runtime<Database>,
    users: ModelInfo<*, TestUser, Uuid>,
    email: Runtime<EmailService>? = null,
    sms: Runtime<SMS>? = null,
) : NotificationBulkDispatcher<TestUser, Uuid, String>(
    info = info,
    cache = cache,
    database = database,
    users = users,
    email = email,
    sms = sms,
    contentSerializer = String.serializer()
) {
    context(server: ServerRuntime)
    override suspend fun email(user: TestUser): EmailAddress = user.email.toEmailAddress()

    context(server: ServerRuntime)
    override suspend fun phone(user: TestUser): PhoneNumber = user.phone.toPhoneNumber()

    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: TestUser): Set<String> = emptySet()

    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: TestUser, deadTokens: Set<String>) {
    }

    context(runtime: ServerRuntime)
    override suspend fun makeEmailNotifications(
        user: TestUser,
        notifications: List<Notification<Uuid, String>>,
    ): List<Email> = notifications.map {
        Email(
            subject = it.content,
            to = listOf(EmailAddressWithName(user.email)),
            plainText = it.content
        )
    }

    context(runtime: ServerRuntime)
    override suspend fun makeSmsNotifications(
        user: TestUser,
        notifications: List<Notification<Uuid, String>>,
    ): List<String> = notifications.map { it.content }

    context(runtime: ServerRuntime)
    override suspend fun makePushNotifications(
        user: TestUser,
        notifications: List<Notification<Uuid, String>>,
    ): List<NotificationData> = emptyList()
}
