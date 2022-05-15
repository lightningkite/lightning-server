package com.lightningkite.ktordb

import kotlin.reflect.KClass

interface Database {
    fun <T: Any> collection(clazz: KClass<T>, name: String): FieldCollection<T>
}

inline fun <reified T: Any> Database.collection(name: String = T::class.simpleName!!): FieldCollection<T> {
    return collection(T::class, name)
}