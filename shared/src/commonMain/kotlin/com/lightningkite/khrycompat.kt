package com.lightningkite.khrysalis

import kotlin.reflect.KClass
import kotlin.reflect.KProperty


typealias AnyObject = Any

typealias IsEquatable = Any?
typealias IsHashable = Any?
typealias IsCodable = Any?
typealias IsCodableAndHashable = Any?
typealias IsCodableAndEquatable = Any?
typealias IsEquatableNotNull = Any
typealias IsHashableNotNull = Any
typealias IsCodableNotNull = Any
typealias IsCodableAndHashableNotNull = Any
typealias IsCodableAndEquatableNotNull = Any
typealias ComparableAndHashable<T> = Comparable<T>
typealias ComparableCodableAndHashable<T> = Comparable<T>

interface Equatable {}
interface Hashable: Equatable {}
typealias SomeEnum = Enum<*>

interface Codable
typealias UntypedList = List<*>
typealias UntypedMap = Map<*, *>

@Target(AnnotationTarget.FILE)
annotation class SharedCode

@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.LOCAL_VARIABLE,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPE,
    AnnotationTarget.FILE,
    AnnotationTarget.TYPEALIAS
)
annotation class JsName(val name: String)

@Target(AnnotationTarget.TYPE)
annotation class Escaping

@Target(AnnotationTarget.CLASS)
annotation class SwiftMustBeClass

@Target(AnnotationTarget.PROPERTY)
annotation class Unowned

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Modifies

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class SwiftName(val name: String)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class SwiftNameless()

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class UnownedSelf

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class WeakSelf

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CaptureUnowned(vararg val keys: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CaptureWeak(vararg val keys: String)

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class SwiftExactly(val parameterName: String = "default")

@Target(AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
annotation class SwiftDescendsFrom(val parameterName: String = "default")

@Target(AnnotationTarget.CLASS)
annotation class SwiftProtocolExtends(vararg val names: String)

@Target(AnnotationTarget.FUNCTION)
annotation class DiscardableResult

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.EXPRESSION)
@Retention(AnnotationRetention.SOURCE)
annotation class SwiftReturnType(val text: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class SwiftExtensionWhere(val text: String)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Throws(vararg val exceptionTypes: KClass<*>)

@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE
)
annotation class PlatformSpecific

fun fatalError(@SwiftNameless reason: String = ""): Nothing {
    throw Error(reason)
}
