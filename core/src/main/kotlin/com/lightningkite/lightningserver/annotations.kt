package com.lightningkite.lightningserver

/**
 * Marks declarations that are internal to Lightning Server's implementation.
 * These APIs should not be used by external consumers as they may change without notice.
 */
@RequiresOptIn(
    "This is essentially internal, and its usage is unstable and may change at any time.",
    RequiresOptIn.Level.ERROR
)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
)
public annotation class InternalLightningServerApi

/**
 * Marks declarations meant for engine implementations, such as starting the root execution for
 * something that arrived from outside the server. Server code should use the corresponding
 * `WithMetrics` functions instead.
 */
@RequiresOptIn(
    "This is for engine implementations. Server code should use the corresponding WithMetrics function.",
    RequiresOptIn.Level.ERROR
)
@Target(
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
)
public annotation class EngineApi

/**
 * Marks a method meant to be overridden but not called directly, like `protected`. Call the corresponding
 * `WithMetrics` function instead, which runs it as its own execution through the server's interceptors.
 *
 * Overrides repeat the annotation, which keeps calls through the subclass restricted too.
 */
@RequiresOptIn(
    "This is meant to be overridden, not called. Use the corresponding WithMetrics function.",
    RequiresOptIn.Level.ERROR
)
@Target(AnnotationTarget.FUNCTION)
public annotation class OverrideOnly

/**
 * Marks the Lightning Server DSL for defining servers and endpoints.
 * This annotation prevents accidental use of DSL functions outside their intended context.
 */
@DslMarker
public annotation class LightningServerDsl

/**
 * Marks APIs that require careful consideration before use.
 * These APIs may have subtle behaviors, performance implications, or require deep understanding
 * of the framework's internals. Only use these if you fully understand their implications.
 */
@RequiresOptIn("Only use this if you understand how it's used.")
public annotation class DelicateLightningServerApi