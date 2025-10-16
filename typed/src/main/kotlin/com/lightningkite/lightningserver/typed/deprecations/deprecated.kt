package com.lightningkite.lightningserver.deprecations

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.pathing.arg2
import com.lightningkite.lightningserver.pathing.arg3
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.Access
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.route
import com.lightningkite.lightningserver.typed.startupOnce
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table

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
    path.path(key) bind startupOnce(database, action = action)
}

@Deprecated("Use fetch instead", ReplaceWith("fetch"))
context(_: ServerRuntime)
public suspend fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> Authentication<SUBJECT>.get(): SUBJECT = fetch()

@Deprecated("Renamed to Table", ReplaceWith("Table", "com.lightningkite.services.database.Table"))
public typealias FieldCollection<T> = Table<T>

@Deprecated("Renamed to baseTable()", ReplaceWith("table()"))
context(_: ServerRuntime)
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.baseCollection(): Table<T> = baseTable()

@Deprecated("Renamed to table()", ReplaceWith("table()"))
context(_: ServerRuntime)
public fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.collection(): Table<T> = table()

@Deprecated("Renamed to table()", ReplaceWith("table(auth)"))
context(_: ServerRuntime)
public suspend fun <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.collection(auth: AuthAccess<SUBJECT>): Table<T> = table(auth)

@Deprecated("Renamed to tableName", ReplaceWith("tableName"))
public val <SUBJECT : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>> ModelInfo<SUBJECT, T, ID>.collectionName: String get() = tableName


@Deprecated("Use route.arg1 instead", ReplaceWith("route.arg1", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg1"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec1<A>>, A> Access<REQ, PathSpec1<A>, *>.path1: A get() = route.arg1

@Deprecated("Use route.arg1 instead", ReplaceWith("route.arg1", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg1"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec2<A, B>>, A, B> Access<REQ, PathSpec2<A, B>, *>.path1: A get() = route.arg1

@Deprecated("Use route.arg2 instead", ReplaceWith("route.arg2", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg2"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec2<A, B>>, A, B> Access<REQ, PathSpec2<A, B>, *>.path2: B get() = route.arg2

@Deprecated("Use route.arg1 instead", ReplaceWith("route.arg1", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg1"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec3<A, B, C>>, A, B, C> Access<REQ, PathSpec3<A, B, C>, *>.path1: A get() = route.arg1

@Deprecated("Use route.arg2 instead", ReplaceWith("route.arg2", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg2"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec3<A, B, C>>, A, B, C> Access<REQ, PathSpec3<A, B, C>, *>.path2: B get() = route.arg2

@Deprecated("Use route.arg3 instead", ReplaceWith("route.second", "com.lightningkite.lightningserver.typed.route", "com.lightningkite.lightningserver.pathing.arg3"))
context(runtime: ServerRuntime)
public val <REQ : Request<PathSpec3<A, B, C>>, A, B, C> Access<REQ, PathSpec3<A, B, C>, *>.path3: C get() = route.arg3
