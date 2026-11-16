package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.definition.ServerDefinition

/**
 * A check that runs once the server is built, against the whole server's [ServerDefinition].
 *
 * Use it for configuration invariants that can only be checked once registration is finished, such as
 * "every event has a subscriber". By then every module object is fully initialized. A check has no
 * runtime: it can't read settings or touch services. Throw to fail the build.
 *
 * A check registered in a module still sees the whole server: [build][ServerBuilder.build] collects the
 * checks of a builder and all its modules, and runs them against the final result, modules' checks first.
 * Checks are not part of the [ServerDefinition]; they are discarded after the build.
 */
public fun interface BuildCheck {
    /** Checks [definition], the [ServerDefinition] of the whole server being built. */
    public fun check(definition: ServerDefinition)
}
