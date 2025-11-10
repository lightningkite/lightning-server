package com.lightningkite.lightningserver

/**
 * Marks declarations that are internal to Lightning Server's implementation.
 * These APIs should not be used by external consumers as they may change without notice.
 */
public annotation class InternalLightningServerApi

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