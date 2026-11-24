package com.lightningkite.lightningserver.terraform

import com.lightningkite.services.terraform.TerraformNeed

/**
 * Source for retrieving deployment secrets like API keys, passwords, and credentials.
 *
 * Implementations can retrieve secrets from various locations:
 * - Environment variables ([EnvironmentSecretSource])
 * - Encrypted files ([EncryptedFileSecretSource])
 * - Cloud secret managers (AWS Secrets Manager, etc.)
 * - Multiple sources with fallback ([ManySecretSources])
 */
public interface SecretSource {
    /** Display name for this secret source (e.g., "environment", "~/.secrets/prod.json.enc") */
    public val name: String

    /**
     * Retrieves the value for a required secret.
     * @throws IllegalStateException if the secret is not found and no fallback is available
     */
    public fun <T> get(need: TerraformNeed<T>): T =
        (getBoxed(need) ?: throw IllegalStateException("Missing secret ${need.name}, no backup methodology established")).value

    /**
     * Retrieves the stored value for a secret, boxed, or `null` if this source has no entry for it.
     *
     * This - not [getOrNull] - is the operation implementations provide, because a need of nullable
     * type may legitimately be stored *as* null; without the box such a need looks unset forever and
     * is re-prompted on every deploy.
     */
    public fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>?

    /**
     * Retrieves the value for a secret, or null if not found.
     *
     * Only meaningful for needs of non-nullable type; for a nullable need use [getBoxed] to tell
     * "stored as null" from "not stored".
     */
    public fun <T> getOrNull(need: TerraformNeed<T>): T? = getBoxed(need)?.value
}

/**
 * A value that is present in a [SecretSource], boxed so that a stored `null` is distinguishable
 * from the absence of any stored value.
 */
@JvmInline
public value class Stored<out T>(public val value: T)

/**
 * A [SecretSource] that can interactively prompt the user for missing secrets.
 * Typically used during development or initial deployment setup.
 */
public interface InteractiveSecretSource {
    /**
     * Prompts the user to provide a value for the given secret.
     * @return The value entered by the user
     */
    public fun <T> prompt(need: TerraformNeed<T>): T
}

/**
 * A [SecretSource] that can both retrieve and store secrets, with interactive prompting support.
 * When a secret is not found, it prompts the user and stores the entered value for future use.
 */
public interface PopulatableSecretSource : SecretSource, InteractiveSecretSource {
    /**
     * Stores a secret value in this source.
     */
    public fun <T> set(need: TerraformNeed<T>, value: T)

    /**
     * Gets a secret, prompting the user if not found.
     * The prompted value is automatically stored for future use.
     */
    override fun <T> get(need: TerraformNeed<T>): T {
        val stored = getBoxed(need) ?: return prompt(need)
        return stored.value
    }

    /**
     * Default implementation that prompts via terminal/console input.
     * Displays instructions and handles default values.
     */
    override fun <T> prompt(need: TerraformNeed<T>): T {
        val typed = need.readFromTerminal("to be stored in $name")
        set(need, typed)
        return typed
    }
}

/**
 * A [SecretSource] that reads secrets from environment variables.
 * Secrets are expected to be in the format `LS_SECRET_{NAME}`.
 *
 * Example: For a secret named "AWS_ACCESS_KEY_ID", this looks for an environment
 * variable named "LS_SECRET_AWS_ACCESS_KEY_ID".
 *
 * This is useful for CI/CD environments where secrets are injected as environment variables.
 */
public object EnvironmentSecretSource : SecretSource {
    override val name: String = "environment"
    override fun <T> getBoxed(need: TerraformNeed<T>): Stored<T>? =
        System.getenv("LS_SECRET_${need.name}")?.let { Stored(need.serializer.parse(it)) }
}

/*
 * TODO: API Recommendations for SecretSource
 *
 * 2. PasswordFetcher.read() stores passwords in plain String, which can't be zeroed from memory. Consider
 *    using CharArray or ByteArray that can be explicitly cleared for better security.
 *
 *
 * 4. The runGuiEditor and runTerminalEditor functions are useful but tightly coupled to Swing/console.
 *    Consider abstracting the UI layer to support different environments (headless servers, web UIs, etc.).
 *
 * 5. Consider adding validation/type checking when setting secrets to catch errors early (e.g., detecting
 *    if a value can be parsed as the expected type before storing it).
 *
 * 6. The PBKDF2 iteration count (100,000) is reasonable for 2025 but may need adjustment over time.
 *    Consider making it configurable or gradually increasing it in future versions.
 */
