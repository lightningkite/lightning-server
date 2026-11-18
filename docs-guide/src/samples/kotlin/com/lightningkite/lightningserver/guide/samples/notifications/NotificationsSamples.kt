package com.lightningkite.lightningserver.guide.samples.notifications

// region notify-imports
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.notifications.*
import com.lightningkite.lightningserver.notifications.events.event
import com.lightningkite.lightningserver.notifications.subscriptions.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.*
import com.lightningkite.services.email.*
import com.lightningkite.services.notifications.NotificationData
import com.lightningkite.services.notifications.NotificationService
import com.lightningkite.services.notifications.TestNotificationService
import com.lightningkite.services.sms.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.*
import kotlin.uuid.Uuid
import com.lightningkite.services.notifications.Notification as PushNotification
// endregion notify-imports

// region notify-models
@Queryable
data class Customer(
    override val _id: Uuid = Uuid.random(),
    val email: EmailAddress,
    val phone: PhoneNumber? = null,
    val pushTokens: Set<String> = setOf(),
) : HasId<Uuid>

object CustomerAuth : PrincipalType<Customer, Uuid> {
    override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
    override val subjectSerializer: KSerializer<Customer> = Customer.serializer()

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): Customer =
        ShopServer.customers.table().get(id) ?: throw NotFoundException("No customer $id")
}

@Queryable
data class Shipment(
    override val _id: Uuid = Uuid.random(),
    val customer: Uuid,
    val trackingNumber: String,
) : HasId<Uuid>

@Serializable
data class Alert(val title: String, val body: String)
// endregion notify-models

// region notify-server
object ShopServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val email = setting("email", EmailService.Settings())
    val sms = setting("sms", SMS.Settings())
    val push = setting("push", NotificationService.Settings("console"))

    init { register(CustomerAuth) }

    val customers = database.modelInfo<Customer, Customer, Uuid>(
        auth = CustomerAuth.require(),
        tableName = "Customer",
        permissions = { ModelPermissions(read = Condition { it._id eq auth.id }) },
    )

    // The explicit type breaks a cycle: the signal refers to the event, whose type refers back to this.
    val shipments: ModelInfo<Customer, Shipment, Uuid> = database.modelInfo(
        auth = CustomerAuth.require(),
        tableName = "Shipment",
        permissions = { ModelPermissions(read = Condition { it.customer eq auth.id }) },
        signals = { table -> table.postCreate { ShopNotifications.shipped(it) } },
    )

    val notificationInfo = database.modelInfo<Customer, Notification<Uuid, Alert>, Uuid>(
        auth = CustomerAuth.require(),
        tableName = "Notification",
        permissions = { ModelPermissions(read = Condition { it.user eq auth.id }) },
    )

    val notifications = path.path("notifications") module ShopNotifications
}
// endregion notify-server

// region notify-dispatcher
object ShopDispatcher : NotificationBulkDispatcher<Customer, Uuid, Alert>(
    info = ShopServer.notificationInfo,
    cache = ShopServer.cache,
    database = ShopServer.database,
    users = ShopServer.customers,
    contentSerializer = Alert.serializer(),
    email = ShopServer.email,
    sms = ShopServer.sms,
    push = ShopServer.push,
) {
    context(server: ServerRuntime)
    override suspend fun email(user: Customer): EmailAddress? = user.email

    context(server: ServerRuntime)
    override suspend fun phone(user: Customer): PhoneNumber? = user.phone

    context(server: ServerRuntime)
    override suspend fun fcmTokens(user: Customer): Set<String> = user.pushTokens

    context(server: ServerRuntime)
    override suspend fun onFcmTokensDead(user: Customer, deadTokens: Set<String>) {
        ShopServer.customers.table().updateOneById(user._id, Modification { it.pushTokens removeAll deadTokens })
    }

    context(runtime: ServerRuntime)
    override suspend fun makeEmailNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<Email> =
        listOf(
            Email(
                subject = notifications.singleOrNull()?.content?.title ?: "${notifications.size} updates",
                to = listOf(EmailAddressWithName(user.email)),
                plainText = notifications.joinToString("\n") { it.content.body },
            )
        )

    context(runtime: ServerRuntime)
    override suspend fun makeSmsNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<String> =
        notifications.map { it.content.body }

    context(runtime: ServerRuntime)
    override suspend fun makePushNotifications(user: Customer, notifications: List<Notification<Uuid, Alert>>): List<NotificationData> =
        notifications.map { NotificationData(notification = PushNotification(title = it.content.title, body = it.content.body)) }
}
// endregion notify-dispatcher

// region notify-events
object ShopNotifications : ServerBuilder() {
    val handler = path include NotificationEndpoints(
        users = ShopServer.customers,
        dispatcher = ShopDispatcher,
        subscriptions = NonCustomizableSubscriptions(),
    )

    val shipped = handler.event("Shipment Sent", ShopServer.shipments) { event ->
        event.subscribed(sms = null) { occurrence -> setOf(occurrence.subject.customer) }
        event.content { occurrence ->
            val tracking = occurrence.subject.trackingNumber
            { _ -> Alert("Your order shipped", "Track it with $tracking.") }
        }
    }
}
// endregion notify-events

// region notify-test
fun notificationsTest() = ShopServer.testBlocking(settings = {
    database set Database.Settings("ram")
    email set EmailService.Settings("test")
    sms set SMS.Settings("test")
    push set NotificationService.Settings("test")
}) {
    val jane = Customer(email = "jane@example.com".toEmailAddress(), pushTokens = setOf("device-1"))
    execute {
        ShopServer.customers.table().insertOne(jane)
        ShopServer.shipments.table().insertOne(Shipment(customer = jane._id, trackingNumber = "1Z999"))
    }

    val sent = (ShopServer.email() as TestEmailService).lastEmailTo("jane@example.com")
    assertEquals("Your order shipped", sent?.subject)
    val pushed = (ShopServer.push() as TestNotificationService).lastMessageSent
    assertEquals(listOf("device-1"), pushed?.targets)

    val stored = execute { ShopDispatcher.info.table().all().toList() }.single()
    assertEquals(true, stored.inApp?.sent)
    assertNull(stored.sms)
}
// endregion notify-test
