package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId


@Deprecated("REEE")
suspend fun <USER: HasId<*>?, INPUT, OUTPUT> ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT>.implementation(
    user: USER,
    input: INPUT
) = implementation(AuthAndPathParts(AuthAccessor.test(user).authOrNull, null, arrayOf()), input)

@Deprecated("REEE")
suspend fun <USER: HasId<*>?, INPUT, PATH1, OUTPUT> ApiEndpoint<USER, TypedServerPath1<PATH1>, INPUT, OUTPUT>.implementation(
    user: USER,
    path1: PATH1,
    input: INPUT
) = implementation(AuthAndPathParts(AuthAccessor.test(user).authOrNull, null, arrayOf(path1)), input)
