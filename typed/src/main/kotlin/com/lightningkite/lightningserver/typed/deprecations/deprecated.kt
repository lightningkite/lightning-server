package com.lightningkite.lightningserver.deprecations

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.StartupOnce
import com.lightningkite.services.database.Database

@Deprecated(
    "Use the standard syntax",
    ReplaceWith("path.path(key) bind StartupOnce(database, action = action)")
)
context(builder: ServerBuilder)
public fun startupOnce(
    key: String,
    database: Runtime<Database>,
    action: suspend context(ServerRuntime) () -> Unit
): Unit = with(builder) {
    path.path(key) bind StartupOnce(database, action = action)
}