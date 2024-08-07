package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.http.HttpEndpoint

suspend inline fun <reified USER: HasId<*>?, reified INPUT, reified OUTPUT> ApiEndpoint<USER, TypedServerPath0, INPUT, OUTPUT>.test(
    authenticatedAs: USER,
    input: INPUT
): OUTPUT = implementation(
    AuthAndPathParts.test<USER>(user = authenticatedAs),
    input
)
suspend inline fun <reified USER: HasId<*>?, reified INPUT, reified OUTPUT, PATH1> ApiEndpoint<USER, TypedServerPath1<PATH1>, INPUT, OUTPUT>.test(
    authenticatedAs: USER,
    path1: PATH1,
    input: INPUT,
): OUTPUT = implementation(
    AuthAndPathParts.test<USER, PATH1>(user = authenticatedAs, path1 = path1),
    input
)
suspend inline fun <reified USER: HasId<*>?, reified INPUT, reified OUTPUT, PATH1, PATH2> ApiEndpoint<USER, TypedServerPath2<PATH1, PATH2>, INPUT, OUTPUT>.test(
    authenticatedAs: USER,
    path1: PATH1,
    path2: PATH2,
    input: INPUT,
): OUTPUT = implementation(
    AuthAndPathParts.test<USER, PATH1, PATH2>(user = authenticatedAs, path1 = path1, path2 = path2),
    input
)