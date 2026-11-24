package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed

/**
 * A [SecretSource] that checks multiple sources in order, returning the first found value.
 * Useful for implementing fallback chains like: environment variables → encrypted file → prompt user.
 *
 * When prompting for a new value, if multiple sources support storage ([PopulatableSecretSource]),
 * the user is asked where to store the value.
 *
 * Example:
 * ```
 * val secrets = ManySecretSources(
 *     EnvironmentSecretSource,
 *     EncryptedFileSecretSource("production"),
 * )
 * ```
 */
public class ManySecretSources(
    public val sources: List<SecretSource>,
) : SecretSource, InteractiveSecretSource {
    public constructor(vararg sources: SecretSource) : this(sources.toList())

    override val name: String = "Many"

    override fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>? {
        return sources.firstNotNullOfOrNull { it.getBoxed(need) }
    }

    override fun <T> prompt(need: TerraformNeed<T>): T {
        val options = sources.filterIsInstance<PopulatableSecretSource>()
        if (options.isEmpty()) throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")
        val destination = options.singleOrNull()
            ?: readSelection("Where would you like to store '${need.name}'?", options) { it.name }
        val typed = need.readFromTerminal("to be stored in ${destination.name}")
        destination.set(need, typed)
        return typed
    }

    override fun <T> get(need: TerraformNeed<T>): T {
        val stored = getBoxed(need) ?: return prompt(need)
        return stored.value
    }
}
