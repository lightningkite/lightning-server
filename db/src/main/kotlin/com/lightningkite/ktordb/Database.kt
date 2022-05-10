package com.lightningkite.ktordb

import kotlin.reflect.KClass

interface Database {
    fun <T: Any> collection(clazz: KClass<T>, name: String): FieldCollection<T>
}

interface WatchableDatabase: Database {
    override fun <T: Any> collection(clazz: KClass<T>, name: String): WatchableFieldCollection<T>
}

inline fun <reified T: Any> Database.collection(name: String = T::class.simpleName!!): FieldCollection<T> {
    return collection(T::class, name)
}
inline fun <reified T: Any> WatchableDatabase.collection(name: String = T::class.simpleName!!): WatchableFieldCollection<T> {
    return collection(T::class, name)
}