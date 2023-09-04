package com.lightningkite.lightningserver.auth

import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.typeOf

class AuthType(
    val classifier: KClassifier?,
    val arguments: List<AuthType?>,
) {
    infix fun satisfies(other: AuthType): Boolean {
        if(!(classifier as KClass<*>).isSubclassOf(other.classifier as KClass<*>)) return false
        // TODO: This isn't that thorough
        return true
    }
    val subjectHandler: Authentication.SubjectHandler<*, *>? get() = Authentication.subjects[this]
    val authName: String? get() = subjectHandler?.name
    override fun toString(): String = buildString {
        append(classifier.toString())
        if(arguments.isNotEmpty()) append(arguments.joinToString(", ", "<", ">"))
    }
    constructor(type: KType):this(
        classifier = type.classifier,
        arguments = type.arguments.map { it.type?.let { AuthType(it) } }
    )
    companion object {
        val none = AuthType(Unit::class, listOf())
    }
    override fun hashCode(): Int = this.classifier.hashCode() * 31 + arguments.hashCode()
    override fun equals(other: Any?): Boolean = other is AuthType && this.classifier == other.classifier && this.arguments == other.arguments
}

inline fun <reified T> AuthType(): AuthType = AuthType(typeOf<T>())