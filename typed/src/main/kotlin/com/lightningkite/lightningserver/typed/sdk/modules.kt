package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.toSealedMap
import kotlin.uuid.Uuid

/**
 * Wraps a server module or definition with SDK generation metadata.
 *
 * This class associates a [ServerBuilder] or [ServerDefinition] with naming information
 * used during SDK generation. When modules are included in a server using the [module]
 * DSL function, this metadata is registered and used by SDK generators to create
 * properly named client interfaces and implementation classes.
 *
 * ## Purpose
 * - Provides explicit control over generated SDK interface and value names
 * - Enables SDK generators to create properly structured client code
 * - Associates metadata with modules for hierarchical SDK generation
 *
 * ## Usage
 * ```kotlin
 * object UserEndpoints : ServerBuilder() {
 *     // Define endpoints...
 * }
 *
 * object Server : ServerBuilder() {
 *     // Include endpoints as a "module" in the SDK, providing "UserApi" as the name for the generated SDK module.
 *     path.path("users") module UserEndpoints.withSdkInfo("UserApi")
 * }
 * ```
 *
 * @param S The type of server module or definition being wrapped
 * @property value The actual server module or definition
 * @property info The naming information for SDK generation
 *
 * @see Info
 * @see withSdkInfo
 * @see module
 */
public data class SdkModule<S>(
    val value: S,
    val info: Info,
) {
    /**
     * Naming information for SDK generation.
     *
     * This class defines how a module will be named in generated SDK code:
     * - The interface name for the client API contract
     * - The value/property name for accessing instances
     *
     * ## Examples
     * For `Info("UserApi", "user")`:
     * - TypeScript: `interface UserApi { ... }` and `api.user`
     * - Kotlin: `interface UserApi { ... }` and `val user: UserApi`
     *
     * @property interfaceName The name for the generated client interface (PascalCase recommended)
     * @property valueName The name for the property/value accessing the implementation (camelCase recommended).
     *                     Defaults to camelCase version of [interfaceName].
     */
    public data class Info(
        val interfaceName: String,
        val valueName: String = interfaceName.camelCase()
    )

    /**
     * Secondary constructor for creating [SdkModule] with separate name parameters.
     *
     * @param value The server module or definition
     * @param interfaceName The interface name for SDK generation
     * @param valueName The value name for SDK generation
     */
    public constructor(value: S, interfaceName: String, valueName: String) : this(value, Info(interfaceName, valueName))

    private object Default : MutableExtensions.Key<Info>

    public companion object {
        /**
         * Default SDK naming information for modules.
         *
         * When set, this provides default values for [withSdkInfo] extension functions,
         * allowing you to avoid specifying names repeatedly for nested modules.
         *
         * @sample
         * ```kotlin
         * sdkSettings.defaultInfo = SdkModule.Info("SubApi", "sub")
         * val module = MyEndpoints().withSdkInfo() // Uses defaults
         * ```
         */
        public var SdkSettings.defaultInfo: Info? by Default

        private fun String.removeLast(string: String) = if (this != string) removeSuffix(string) else this

        /**
         * Wraps a [ServerBuilder] with SDK generation metadata.
         *
         * This extension provides a convenient way to associate a server module with
         * naming information for SDK generation. Names can be explicitly provided or
         * automatically inferred from the class name.
         *
         * ## Name Inference
         * If not specified, the interface name is inferred from the class name by:
         * 1. Removing "Endpoints" or "Module" suffixes
         * 2. Converting to PascalCase
         * 3. Adding "Api" suffix
         *
         * For example: `UserEndpoints` → `UserApi`
         *
         * The value name defaults to the camelCase version of the interface name,
         * with "Api" suffix removed if present.
         *
         * @param interfaceName The interface name for SDK generation (inferred if not provided)
         * @param valueName The value name for SDK generation (derived from interfaceName if not provided)
         * @return A [SdkModule] wrapping this server builder with the specified metadata
         *
         * @throws IllegalArgumentException if the interface name cannot be inferred (e.g., for anonymous objects)
         *
         * @sample
         * ```kotlin
         * class UserEndpoints : ServerBuilder() {
         *     // Define endpoints...
         * }
         *
         * // Explicit names
         * val module1 = UserEndpoints().withSdkInfo("UserApi", "users")
         *
         * // Inferred: "UserApi" and "user"
         * val module2 = UserEndpoints().withSdkInfo()
         * ```
         */
        public fun <S : ServerBuilder> S.withSdkInfo(
            interfaceName: String = sdkSettings.defaultInfo?.interfaceName ?: this::class.simpleName?.let { it.removeLast("Endpoints").removeLast("Module").pascalCase() + "Api" } ?: throw IllegalArgumentException("Cannot infer name for anonymous object"),
            valueName: String = sdkSettings.defaultInfo?.valueName ?: interfaceName.camelCase().removeSuffix("Api")
        ) : SdkModule<S> =
            SdkModule(this, interfaceName, valueName)

        /**
         * Wraps a [ServerDefinition] with SDK generation metadata.
         *
         * This extension associates a server definition with naming information for SDK generation.
         * Unlike the [ServerBuilder] version, names cannot be inferred and must be provided explicitly.
         *
         * @param interfaceName The interface name for SDK generation
         * @param valueName The value name for SDK generation (defaults to camelCase of interfaceName)
         * @return A [SdkModule] wrapping this server definition with the specified metadata
         *
         * @sample
         * ```kotlin
         * val definition: ServerDefinition = myServer.build()
         * val module = definition.withSdkInfo("MyApi", "myApi")
         * ```
         */
        public fun ServerDefinition.withSdkInfo(
            interfaceName: String,
            valueName: String = interfaceName.camelCase()
        ) : SdkModule<ServerDefinition> =
            SdkModule(this, interfaceName, valueName)
    }
}

/**
 * Includes a server configuration at the specified path as an SDK module.
 *
 * SDK modules are separated into their own interfaces and classes when the SDK
 * is generated.
 *
 * This DSL function registers the module's SDK metadata and includes it in the server
 * at the given path. The metadata is used by SDK generators to create properly structured
 * client code with the specified interface and value names.
 *
 * ## Behavior
 * - Registers the module's [SdkModule.Info] for SDK generation
 * - Includes the module's endpoints at the specified path
 * - Returns the included module for further configuration
 *
 * @receiver The path where the module should be mounted
 * @param module The [SdkModule] containing both the server module and SDK metadata
 * @return The included server module
 */
@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <S : ServerBuilder> PathSpec0.module(module: SdkModule<S>): S {
    builder.extensions[ModuleRegistry][module.value.sdkId] = module.info
    return with(builder) { include(module.value) }
}

/**
 * Includes a server configuration at the specified path as an SDK module.
 *
 * SDK modules are separated into their own interfaces and classes when the SDK
 * is generated.
 *
 * This convenience overload automatically wraps the module with [withSdkInfo], inferring
 * the SDK interface and value names from the module's class name.
 *
 * @receiver The path where the module should be mounted
 * @param module The server module to include (will be wrapped with inferred SDK info)
 * @return The included server module
 *
 * @throws IllegalArgumentException if SDK names cannot be inferred from the class name
 *
 * @sample
 * ```kotlin
 * class UserEndpoints : ServerBuilder() {
 *     // Define endpoints...
 * }
 *
 * with(serverBuilder) {
 *     // SDK info inferred: UserApi / user
 *     path("api" / "users").module(UserEndpoints())
 * }
 * ```
 */
@LightningServerDsl
context(builder: ServerBuilder)
public infix fun <S : ServerBuilder> PathSpec0.module(module: S): S = module(module.withSdkInfo())

/**
 * Includes a server definition at the specified path with SDK generation metadata.
 *
 * This overload allows including pre-built [ServerDefinition] instances (from other servers
 * or modules) with SDK metadata. This is useful for composing servers or reusing definitions.
 *
 * @receiver The path where the definition should be mounted
 * @param module The [SdkModule] wrapping a [ServerDefinition] and SDK metadata
 * @return The included server definition
 *
 * @sample
 * ```kotlin
 * val sharedDefinition: ServerDefinition = sharedServer.build()
 *
 * with(serverBuilder) {
 *     path("shared").module(
 *         sharedDefinition.withSdkInfo("SharedApi", "shared")
 *     )
 * }
 * ```
 */
@LightningServerDsl
context(builder: ServerBuilder)
public infix fun PathSpec0.module(module: SdkModule<ServerDefinition>): ServerDefinition {
    builder.extensions[ModuleRegistry][module.value.thisLayer.sdkId] = module.info
    return with(builder) { include(module.value) }
}



// ============================================================================
// Implementation Details
// ============================================================================
//
// The following code provides internal mechanisms for:
// - Associating unique IDs with modules for tracking
// - Registering SDK metadata in server extensions
// - Retrieving metadata during SDK generation
//
// These details are not part of the public API and may change.


/**
 * Gets or creates a unique SDK ID for this ServerBuilder.
 * IDs are lazily generated and cached in the extensions.
 */
@OptIn(InternalLightningServerApi::class)
private inline val ServerBuilder.sdkId get() = moduleId

/**
 * Retrieves the SDK ID from a ServerDefinition.Module if it exists.
 */
@OptIn(InternalLightningServerApi::class)
private inline val ServerDefinition.Module.sdkId get() = moduleId

/**
 * Extension key for storing the registry of module SDK metadata.
 * Maps module UUIDs to their SdkModule.Info.
 *
 * This registry is intentionally non-cascading - each module maintains
 * its own list of child module metadata without inheriting parent registries.
 */
private object ModuleRegistry : MutableExtensions.WritableKey<MutableMap<Uuid, SdkModule.Info>, Map<Uuid, SdkModule.Info>> {
    override fun default(): MutableMap<Uuid, SdkModule.Info> = HashMap()
    override fun MutableMap<Uuid, SdkModule.Info>.include(other: Map<Uuid, SdkModule.Info>) {
        /*No-op, we want to keep registered modules specific per-module, not cascading.*/
    }
    override fun seal(data: Map<Uuid, SdkModule.Info>): Map<Uuid, SdkModule.Info> = data.toSealedMap()
}

/**
 * Retrieves the SDK metadata for a child module from a parent module's registry.
 *
 * @param other The child module to look up
 * @return The SDK metadata if registered, null otherwise
 */
internal fun ServerDefinition.Module.getModuleInfo(other: ServerDefinition.Module): SdkModule.Info? {
    return extensions[ModuleRegistry]?.get(other.sdkId)
}