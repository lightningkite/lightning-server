package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.services.data.Unsafe

@InternalLightningServerApi
@Unsafe("This must follows the rules of inheritance for execution scopes, and any code should be intercepted by the server's execution interceptors.")
public fun Engine.createRuntime(scope: Execution): ServerRuntime = ExecutionRuntime(this, scope)

private class ExecutionRuntime(
    engine: Engine,
    override val execution: Execution,
) : ServerRuntime, Engine by engine

