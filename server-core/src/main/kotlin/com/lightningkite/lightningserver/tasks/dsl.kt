package com.lightningkite.lightningserver.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.CoroutineContext

inline fun <reified INPUT> task(name: String, noinline implementation: suspend CoroutineScope.(INPUT)->Unit) = task(name, serializer<INPUT>(), implementation)
fun <INPUT> task(name: String, serializer: KSerializer<INPUT>, implementation: suspend CoroutineScope.(INPUT)->Unit) = Task(name, serializer, implementation)
