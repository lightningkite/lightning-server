package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.firstOrNull

/**
 * Retrieves the [Model] with [id], or throws a [NotFoundException] with the provided [message] if not found.
 * */
public suspend fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.getOrNotFound(
    id: ID,
    message: String = "${serializer.descriptor.serialName.substringAfterLast('.')} $id not found",
): Model {
    return find(Condition.OnField(serializer._id(), Condition.Equal(id)), limit = 1).firstOrNull()
        ?: throw NotFoundException(message)
}

/**
 * Retrieves the [Model] with [id], or throws a [BadRequestException] with the provided [message] if not found.
 * */
public suspend fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.getOrBadRequest(
    id: ID,
    message: String = "${serializer.descriptor.serialName.substringAfterLast('.')} $id not recognized",
): Model {
    return find(Condition.OnField(serializer._id(), Condition.Equal(id)), limit = 1).firstOrNull()
        ?: throw BadRequestException(message)
}

/**
 * Retrieves the [Model] with [id], or throws a [ForbiddenException] with the provided [message] if not found.
 * */
public suspend fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.getOrForbidden(
    id: ID,
    message: String = "${serializer.descriptor.serialName.substringAfterLast('.')} $id not recognized",
): Model {
    return find(Condition.OnField(serializer._id(), Condition.Equal(id)), limit = 1).firstOrNull()
        ?: throw ForbiddenException(message)
}

/**
 * Retrieves the [Model] with [id], or throws an [IllegalStateException] with the provided [message] if not found.
 * */
public suspend fun <Model : HasId<ID>, ID : Comparable<ID>> Table<Model>.getOrThrow(
    id: ID,
    message: String = "${serializer.descriptor.serialName.substringAfterLast('.')} $id not recognized",
): Model =
    get(id) ?: throw IllegalStateException(message)
