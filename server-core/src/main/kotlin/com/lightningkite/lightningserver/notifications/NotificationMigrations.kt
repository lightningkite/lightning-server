package com.lightningkite.lightningserver.notifications

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.serializerOrContextual
import kotlinx.coroutines.flow.toList

object NotificationMigrations {
    object Behavior {
        // Default behavior is if a default subscription exists, then it will be inserted if not inserted already when the migration is run

        const val REPLACE_WITH_DEFAULT = "MIGRATIONS BEHAVIOR - REPLACE WITH DEFAULT"
        const val IGNORE_DEFAULT = "MIGRATIONS BEHAVIOR - IGNORE DEFAULT"

        val tags = setOf(REPLACE_WITH_DEFAULT, IGNORE_DEFAULT)
    }

    suspend fun <USER : HasId<UID>, UID : Comparable<UID>, CONTENT : NotificationContent> updateDefaults(
        users: FieldCollection<USER>,
        notifications: NotificationEndpoints<USER, UID, CONTENT>
    ) {
        val types = notifications.eventRegistry.registered

        val replace = types.filter { Behavior.REPLACE_WITH_DEFAULT in it.tags }
        val insertIfNotPresent = types.filter { Behavior.IGNORE_DEFAULT !in it.tags }

        val allUsers = users.find(Condition.Always, maxQueryMs = 1_000_000).toList()

        val replacements = replace
            .flatMap { type ->
                allUsers.mapNotNull {
                    type.defaultSubscription(it)?.toEventSubscription(type, it)
                }
            }
            .associateBy { it._id }

        val upserts = HashMap<UserEventType<UID>, EventSubscription<UID>>(insertIfNotPresent.size * allUsers.size)
        for (type in insertIfNotPresent) {
            for (user in allUsers) {
                type.defaultSubscription(user)?.let {
                    upserts[UserEventType(user._id, type.type)] = it.toEventSubscription(type, user)
                }
            }
        }

        // Replacing takes priority
        (replacements.keys.intersect(upserts.keys)).forEach {
            upserts.remove(it)
        }

        notifications.subscriptions.collection()
            .find(
                notifications.subscriptions.condition { it._id inside upserts.keys }
            )
            .toList()
            .forEach {
                upserts.remove(it._id)
            }

        notifications.subscriptions.collection()
            .deleteManyIgnoringOld(
                notifications.subscriptions.condition { it._id inside replacements.keys }
            )

        notifications.subscriptions.collection().insertMany(
            replacements.values + upserts.values
        )
    }
}