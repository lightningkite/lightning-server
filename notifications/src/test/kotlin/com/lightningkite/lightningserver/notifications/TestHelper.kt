//package com.lightningkite.lightningserver.notifications
//
//import com.lightningkite.EmailAddress
//import com.lightningkite.PhoneNumber
//import com.lightningkite.lightningserver.auth.PrincipalType
//import com.lightningkite.lightningserver.auth.require
//import com.lightningkite.lightningserver.definition.Runtime
//import com.lightningkite.lightningserver.runtime.ServerRuntime
//import com.lightningkite.lightningserver.typed.ModelInfo
//import com.lightningkite.lightningserver.typed.modelInfo
//import com.lightningkite.services.cache.Cache
//import com.lightningkite.services.data.GenerateDataClassPaths
//import com.lightningkite.services.database.Database
//import com.lightningkite.services.database.HasId
//import com.lightningkite.services.database.ModelPermissions
//import com.lightningkite.services.email.Email
//import com.lightningkite.services.email.EmailAddressWithName
//import com.lightningkite.services.email.EmailService
//import com.lightningkite.services.notifications.NotificationData
//import com.lightningkite.services.notifications.NotificationService
//import com.lightningkite.services.sms.SMS
//import com.lightningkite.toEmailAddress
//import com.lightningkite.toPhoneNumber
//import kotlinx.serialization.KSerializer
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.builtins.serializer
//import kotlin.uuid.Uuid
//
//
///**
// * Test notification content enum for verifying correct notification types.
// * Each value represents a different notification event type with associated
// * title and message template.
// */
//@Serializable
//enum class TestNotificationContent(val title: String, val messageTemplate: String) {
//    MODEL_CREATED("New Model Created", "Model '%s' was just created."),
//    MODEL_UPDATED("Model Updated", "Model '%s' was updated."),
//    MODEL_DELETED("Model Deleted", "Model '%s' was deleted."),
//    ORDER_PLACED("Order Placed", "Your order #%s has been placed."),
//    ORDER_SHIPPED("Order Shipped", "Your order #%s has shipped!"),
//    COMMENT_ADDED("New Comment", "Someone commented on '%s'."),
//    USER_MENTIONED("You Were Mentioned", "You were mentioned in '%s'.");
//
//    fun format(vararg args: Any): String = messageTemplate.format(*args)
//}
//
///**
// * Test user model for notification tests.
// */
//@Serializable
//@GenerateDataClassPaths
//data class TestUser(
//    override val _id: Uuid = Uuid.random(),
//    val name: String = "Test User",
//    val email: String = "test@example.com",
//    val phone: String = "1234567890",
//    val fcmTokens: Set<String> = setOf("fcm-token-1")
//) : HasId<Uuid> {
//    companion object : PrincipalType<TestUser, Uuid> {
//        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
//        override val subjectSerializer: KSerializer<TestUser> = serializer()
//
//        context(server: ServerRuntime)
//        override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
//    }
//}
//
///**
// * Test model entity for triggering notification events.
// */
//@Serializable
//@GenerateDataClassPaths
//data class TestModel(
//    override val _id: Uuid = Uuid.random(),
//    val name: String = "Test Model",
//    val value: Int = 0,
//    val ownerId: Uuid = Uuid.random()
//) : HasId<Uuid>
//
///**
// * Abstract base dispatcher with standard implementations for testing.
// * Provides concrete implementations of abstract methods in NotificationBulkDispatcher.
// */
//abstract class TestDispatcherBase(
//    info: ModelInfo<TestUser, Notification<Uuid, TestNotificationContent>, Uuid>,
//    cache: Runtime<Cache>,
//    database: Runtime<Database>,
//    users: ModelInfo<*, TestUser, Uuid>,
//    email: Runtime<EmailService>? = null,
//    sms: Runtime<SMS>? = null,
//    push: Runtime<NotificationService>? = null
//) : NotificationBulkDispatcher<TestUser, Uuid, TestNotificationContent>(
//    info = info,
//    cache = cache,
//    database = database,
//    users = users,
//    email = email,
//    sms = sms,
//    push = push,
//    contentSerializer = TestNotificationContent.serializer()
//) {
//    context(server: ServerRuntime)
//    override suspend fun email(user: TestUser): EmailAddress = user.email.toEmailAddress()
//
//    context(server: ServerRuntime)
//    override suspend fun phone(user: TestUser): PhoneNumber = user.phone.toPhoneNumber()
//
//    context(server: ServerRuntime)
//    override suspend fun fcmTokens(user: TestUser): Set<String> = user.fcmTokens
//
//    val deadTokensReported = mutableSetOf<String>()
//
//    context(server: ServerRuntime)
//    override suspend fun onFcmTokensDead(user: TestUser, deadTokens: Set<String>) {
//        deadTokensReported.addAll(deadTokens)
//    }
//
//    context(runtime: ServerRuntime)
//    override suspend fun makeEmailNotifications(
//        user: TestUser,
//        notifications: List<Notification<Uuid, TestNotificationContent>>
//    ): List<Email> = notifications.map { notif ->
//        Email(
//            subject = notif.content.title,
//            to = listOf(EmailAddressWithName(user.email)),
//            plainText = notif.content.format(user.name)
//        )
//    }
//
//    context(runtime: ServerRuntime)
//    override suspend fun makeSmsNotifications(
//        user: TestUser,
//        notifications: List<Notification<Uuid, TestNotificationContent>>
//    ): List<String> = notifications.map { notif ->
//        "${notif.content.title}: ${notif.content.format(user.name)}"
//    }
//
//    context(runtime: ServerRuntime)
//    override suspend fun makePushNotifications(
//        user: TestUser,
//        notifications: List<Notification<Uuid, TestNotificationContent>>
//    ): List<NotificationData> = emptyList()  // Push notifications not needed for most tests
//}
//
///**
// * Extension function to create a test ModelInfo with full permissions.
// */
//inline fun <reified T : HasId<ID>, reified ID : Comparable<ID>> Runtime<Database>.testModelInfo(): ModelInfo<TestUser, T, ID> =
//    modelInfo(
//        TestUser.require(),
//        permissions = { ModelPermissions.allowAll() }
//    )
