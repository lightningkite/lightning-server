package com.lightningkite.lightningserver.definition.builder

import com.lightningkite.lightningserver.definition.ServerDefinition

/**
 * A check that runs once, right after the [ServerBuilder][ServerBuilder]
 * that registered it is built.
 *
 * Use it for configuration invariants that can only be checked once registration is finished, such as
 * "every event has a subscriber". By then every module object is fully initialized. A check has no
 * runtime: it can't read settings or touch services. Throw to fail the build.
 *
 * Checks are not part of the [ServerDefinition]; they run immediately after
 * [build][ServerBuilder.build] completes and are then discarded.
 */
public fun interface BuildCheck {
    /** Checks [definition], the [ServerDefinition] just built from the builder that registered this check. */
    public fun check(definition: ServerDefinition)
}
