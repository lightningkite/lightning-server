package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi

@InternalLightningServerApi
public fun Engine.executionRuntime(scope: Execution): ServerRuntime = ExecutionRuntime(this, scope)

@InternalLightningServerApi
public inline fun <T> Engine.execute(scope: Execution, action: ServerRuntime.() -> T): T = executionRuntime(scope).action()

private class ExecutionRuntime(
    engine: Engine,
    override val execution: Execution,
) : ServerRuntime, Engine by engine

