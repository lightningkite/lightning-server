package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.services.data.Unsafe

@OptIn(Unsafe::class) // SAFETY: The invariant on the constructor is really to just use this function. This is the safe one.
public fun Execution.ID.toExternal(): ExecutionId = ExecutionId(this.raw)

@OptIn(InternalLightningServerApi::class)
public fun ExecutionId.toInternal(): Execution.ID = Execution.ID(this.raw)