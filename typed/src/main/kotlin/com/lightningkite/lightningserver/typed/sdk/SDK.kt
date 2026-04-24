package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.ServerRuntimeBase
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.data.*
import com.lightningkite.services.kfile.KFile
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

/**
 * SDK generation framework for Lightning Server applications.
 *
 * This namespace contains all classes, interfaces, and utilities for generating
 * type-safe client SDKs from Lightning Server API definitions. It supports generating
 * SDKs in multiple languages and formats.
 *
 * ## Core Components
 * - [SDK.Format] - Interface for implementing custom SDK generators
 * - [SDK.Data] - Hierarchical representation of the API structure
 * - [SDK.Module] - Processed module representation for code generation
 * - [SDK.Function] - Unified representation of API endpoints and WebSocket handlers
 *
 * ## Built-in Formats
 * - [TypescriptFetcherSdk] - Generates TypeScript SDK with Fetcher-based HTTP clients
 * - [FetcherSdk] - Generates Kotlin SDK with Fetcher-based HTTP clients
 *
 * ## Basic Usage
 * ```kotlin
 * // Define your format
 * val format = TypescriptFetcherSdk(rootInfo = SdkModule.Info("MyApi"))
 *
 * // Generate SDK to a ZIP file
 * val zipOut = ZipOutputStream(FileOutputStream("sdk.zip"))
 * Archive.zip(zipOut).use { archive ->
 *     format.writeUsingDefaultSettings(Server, archive)
 * }
 * ```
 *
 * @see SDK.Format
 * @see TypescriptFetcherSdk
 * @see FetcherSdk
 */
public object SDK {
    /**
     * Common documentation and metadata interface for API endpoints and handlers.
     *
     * This interface provides standardized documentation properties that can be extracted
     * from server API handlers and used to generate SDK documentation, comments, and
     * method signatures in client code.
     *
     * @property summary A brief one-line description of the endpoint's purpose
     * @property functionName The generated function name for this endpoint in client code.
     *                        Defaults to converting [summary] to camelCase.
     * @property description A detailed multi-line description of the endpoint's behavior
     * @property auth The authentication/authorization requirements for accessing this endpoint
     * @property inputType The serializer for the input/request type
     * @property outputType The serializer for the output/response type
     */
    public interface Documentable {
        public val summary: String
        public val functionName: String get() = summary.functionCase()
        public val description: String
        public val auth: AuthRequirement<*>

        public val inputType: KSerializer<*>
        public val outputType: KSerializer<*>
    }

    /**
     * Represents a specific SDK output format that can generate client code for a Lightning Server application.
     *
     * Implementations of this interface generate SDK files in various languages and formats
     * (e.g., TypeScript, Kotlin) by analyzing the server's API structure and writing the
     * appropriate client code to an [Archive].
     *
     * ## Purpose
     * SDK formats enable automatic generation of type-safe client libraries that can communicate
     * with your Lightning Server. This eliminates manual API client development and ensures
     * clients stay synchronized with server changes.
     *
     * ## Built-in Implementations
     * - [TypescriptFetcherSdk] - Generates TypeScript with Fetcher-based HTTP clients
     * - [FetcherSdk] - Generates Kotlin with Fetcher-based HTTP clients
     *
     * ## Usage Example
     * ```kotlin
     * val format = TypescriptFetcherSdk(
     *     rootInfo = SdkModule.Info("MyApi"),
     *     fileStructure = TypescriptFetcherSdk.Files.MultipleFiles(
     *         modelsFilename = "models.ts",
     *         interfaceFilename = "MyApi.ts",
     *         liveFilename = "LiveMyApi.ts"
     *     )
     * )
     * ```
     *
     * ## Creating a Custom Format
     *
     * To create a custom SDK format, implement the [Format] interface and override the [write] method.
     * Your implementation will have access to the [ServerRuntime] context, which provides all the
     * information needed to generate client code.
     *
     * ### Step-by-Step Guide
     *
     * **1. Extract API Structure**
     *
     * Use `server.server.sdk()` to extract the hierarchical API structure:
     * ```kotlin
     * val apiData: SDK.Data = server.server.sdk(rootInfo)
     * ```
     *
     * **2. Process into Modules**
     *
     * Convert the raw data into a more convenient module structure:
     * ```kotlin
     * val rootModule: SDK.Module = apiData.processToModules()
     * ```
     *
     * **3. Generate Type Definitions**
     *
     * Use `server.usedTypes()` to access all registered type serializers.
     * You'll need to convert Kotlin types to your target language's type system.
     *
     * **4. Generate API Client Code**
     *
     * Iterate through modules and functions to generate client methods:
     * ```kotlin
     * fun generateModule(module: SDK.Module, archive: Archive) {
     *     // Generate functions for this module
     *     module.functions.forEach { function ->
     *         when (function) {
     *             is SDK.Function.Endpoint -> generateHttpMethod(function)
     *             is SDK.Function.Websocket -> generateWebSocketMethod(function)
     *         }
     *     }
     *
     *     // Recursively generate child modules
     *     module.children.forEach { child ->
     *         generateModule(child, archive.sub(child.path.segments.first()))
     *     }
     * }
     * ```
     *
     * **5. Write Files to Archive**
     *
     * Use the [Archive] API to write generated code:
     * ```kotlin
     * archive.sink("api-client.js") {
     *     writeString(generatedCode)
     * }
     * ```
     *
     * ### Complete Example
     *
     * ```kotlin
     * class SimpleJsonFormat(private val rootInfo: SdkModule.Info) : SDK.Format {
     *     context(server: ServerRuntime)
     *     override fun write(archive: Archive) {
     *         // Extract and process API structure
     *         val apiData = server.server.sdk(rootInfo)
     *         val rootModule = apiData.processToModules()
     *
     *         // Generate API documentation in JSON
     *         archive.sink("api.json") {
     *             writeString(buildString {
     *                 append("{\n")
     *                 append("  \"name\": \"${rootModule.info.interfaceName}\",\n")
     *                 append("  \"endpoints\": [\n")
     *
     *                 rootModule.functions.forEachIndexed { index, function ->
     *                     if (index > 0) append(",\n")
     *                     append("    {\n")
     *                     append("      \"name\": \"${function.functionName}\",\n")
     *                     append("      \"path\": \"${function.path}\",\n")
     *                     append("      \"summary\": \"${function.summary}\"\n")
     *                     append("    }")
     *                 }
     *
     *                 append("\n  ]\n")
     *                 append("}")
     *             })
     *         }
     *     }
     * }
     *
     * // Usage
     * val format = SimpleJsonFormat(SdkModule.Info("MyApi"))
     * format.writeUsingDefaultSettings(serverBuilder, Archive.folder(outputDir))
     * ```
     *
     * ### Tips for Implementation
     *
     * - **Type Mapping**: Create a mapping from Kotlin types to your target language
     * - **Serialization**: Handle custom serializers and polymorphic types appropriately
     * - **Documentation**: Extract comments from [Documentable] properties for API docs
     * - **Path Parameters**: Use `function.arguments` to get ordered parameters including path wildcards
     * - **Authentication**: Check `function.auth` to generate appropriate auth headers
     * - **File Structure**: Use [Archive.sub] to organize generated code into directories
     *
     * ### Experimental
     * [Format] is currently listed as experimental due to its use of [Archive]. This interface
     * can be inherited from safely, but be aware that [Archive] may change in future versions,
     * which could cause breaking changes.
     *
     * with(serverRuntime) {
     *     Archive.zip(ZipOutputStream(outputStream)).use { archive ->
     *         format.write(archive)
     *     }
     * }
     * ```
     *
     * @see TypescriptFetcherSdk
     * @see FetcherSdk
     * @see Archive
     */
    @SubclassOptInRequired(ExperimentalLightningServer::class)
    @OptIn(ExperimentalLightningServer::class)
    public interface Format {
        /**
         * Writes the SDK files to the provided archive.
         *
         * This method generates all necessary SDK files (type definitions, interfaces,
         * implementations, etc.) and writes them to the archive using the [Archive] API.
         *
         * The implementation should:
         * 1. Analyze the server's API structure via the [ServerRuntime] context
         * 2. Generate appropriate client code for the target language/platform
         * 3. Write all files to the archive using `Archive.entry()` and `Archive.sub()`
         *
         * ## Data Transformation
         *
         * LightningServer provides several helper methods to assist with creating your
         * own [SDK.Format] implementations.
         *
         * All [ServerRuntime] data is provided when `write` is called, but this data can be
         * difficult to parse into client code with proper api module structure defined during
         * the server-definition phase.
         *
         * To help with this, use the provided `ServerDefinition.sdk()` extension to parse
         * relevant api information from the [ServerDefinition]. This produces
         * an [SDK.Data] object, which is a hierarchical representation of server API structure.
         *
         * Most client code follows similar patterns, and [SDK.Module] is provided to mimic
         * these patterns in an easier-to-use format. Use `SDK.Data.processToModules()` to
         * create a [Module] for client code generation.
         *
         * @param archive The archive where SDK files will be written. Can be a filesystem
         *                directory, ZIP file, or single output stream depending on the
         *                [Archive] implementation used.
         *
         * @receiver server The [ServerRuntime] providing access to API definitions,
         *                  type serializers, and server metadata
         *
         * @sample
         * ```kotlin
         * class MyCustomFormat : SDK.Format {
         *     context(server: ServerRuntime)
         *     override fun write(archive: Archive) {
         *         val apiData = server.server.sdk()
         *         archive.sink("api-client.txt") {
         *             writeString("Generated client for ${apiData.layer.info.interfaceName}")
         *         }
         *     }
         * }
         * ```
         */
        context(server: ServerRuntime)
        public fun write(archive: Archive)
    }

    /**
     * Hierarchical representation of server API structure for SDK generation.
     *
     * This class organizes the server's API endpoints, modules, and their relationships
     * into a tree structure that can be traversed and processed by SDK generators.
     *
     * Each [Data] node represents a module/namespace in the API hierarchy and contains:
     * - A [Layer] with module metadata and endpoints
     * - Child modules as nested [Data] instances
     *
     * @property layer The module's metadata and endpoint definitions
     * @property children Nested sub-modules with their relative paths
     *
     * @see ServerDefinition.sdk
     */
    public data class Data(
        val layer: Layer,
        val children: List<Locationed<PathSpec0, Data>>,
    ) {
        /**
         * Metadata and endpoint information for a single module/layer in the API hierarchy.
         *
         * @property info The module's naming and identification information
         * @property endpoints The endpoints for this layer, grouped by their client interface.
         *                     The location of the interface is the location of the module with that interface.
         *                     The associated endpoints already include this location prefixed in the PathSpecMap.
         */
        public data class Layer(
            val info: SdkModule.Info,
            val endpoints: Map<Locationed<PathSpec0, InterfaceInfo>?, PathSpecMap<ServerApiEndpoints>>,
        )

        /**
         * A node in the API tree representing a module with its full context.
         *
         * Nodes are created when traversing the API tree and provide both the module's
         * data and its position/lineage within the hierarchy.
         *
         * @property ancestors The chain of parent layers from root to this node (excluding this node)
         * @property absolutePath The full path from the API root to this module
         * @property layer The module's metadata and endpoints
         * @property depth The nesting depth of this node (0 = root, 1 = first level, etc.)
         */
        public data class Node(
            val ancestors: List<Layer>,
            val absolutePath: PathSpec0,
            val layer: Layer,
        ) {
            public val depth: Int get() = ancestors.size
        }

        /**
         * Returns a sequence of all nested modules as nodes using a depth-first search.
         *
         * This method traverses the entire API tree and yields each module as a [Node]
         * with full context about its position in the hierarchy. Modules are visited
         * depth-first and sorted alphabetically by interface name at each level.
         *
         * @return A sequence of [Node] instances representing the flattened API tree
         */
        public fun asSequence(): Sequence<Node> = sequence {
            suspend fun SequenceScope<Node>.yield(ancestors: List<Layer>, path: PathSpec0, data: Data) {
                yield(Node(ancestors, path, data.layer))
                for ((modPath, child) in data.children.sortedBy { it.item.layer.info.interfaceName })
                    yield(ancestors + data.layer, path + modPath, child)
            }

            yield(emptyList(), PathSpec.root, this@Data)
        }
    }

    /**
     * Represents a callable API function (HTTP endpoint or WebSocket handler).
     *
     * This sealed interface provides a unified view of different types of API functions
     * with their documentation, path, arguments, and relationship to client interfaces.
     *
     * @property path The relative path of the function to its containing module
     * @property fromInterface The client interface this function belongs to, if any.
     *                         Null for functions declared directly on a module.
     * @property arguments The ordered list of arguments for this function (path parameters + input body)
     */
    public sealed interface Function : Documentable {
        public val path: PathSpec
        public val fromInterface: InterfaceInfo?
        public val arguments: List<Argument>

        /**
         * Represents a single argument to an API function.
         *
         * @property name The argument name (from path parameter or "input" for request body)
         * @property type The serializer for this argument's type
         */
        public data class Argument(val name: String, val type: KSerializer<*>)

        public data class Endpoint(
            val handler: ApiHttpHandler<*, *, *, *>,
            val endpoint: HttpEndpoint<PathSpec>,
            override val fromInterface: InterfaceInfo?,
            override val functionName: String = handler.functionName.functionCase(),
        ) : Function, Documentable by handler {
            override val path: PathSpec get() = endpoint.path

            override val arguments: List<Argument>
                get() = path.wildcards
                    .map { Argument(it.name, it.serializer) }
                    .plus(
                        if (inputType.isUnit()) emptyList()
                        else listOf(Argument("input", inputType))
                    )
        }

        public data class Websocket(
            val handler: ApiWebsocketHandler<*, *, *, *, *>,
            override val path: PathSpec,
            override val fromInterface: InterfaceInfo?,
            override val functionName: String = handler.functionName.functionCase(),
        ) : Function, Documentable by handler {
            override val arguments: List<Argument>
                get() =
                    path.wildcards.map { Argument(it.name, it.serializer) }
        }
    }

    /**
     * A processed module representation optimized for SDK code generation.
     *
     * This class represents a single module in the API hierarchy after processing
     * from [Data]. It organizes functions, interfaces, and child modules in a way
     * that's convenient for generating client code.
     *
     * @property info The module's naming and identification information
     * @property path The relative path of the module to its parent module
     * @property extendsInterfaces The client interfaces this module implements.
     *                            Only includes direct interfaces (supertypes filtered out).
     * @property functions All functions available in this module (both declared and inherited)
     * @property children Nested child modules
     * @property declaredFunctions Functions declared directly on this module (not from interfaces)
     * @property functionOverrides Functions that implement interface methods
     */
    public data class Module(
        val info: SdkModule.Info,
        val path: PathSpec0,
        val extendsInterfaces: List<Locationed<PathSpec0, InterfaceInfo>>,
        val functions: List<Function>,
        val children: List<Module>,
    ) {
        public val declaredFunctions: List<Function> get() = functions.filter { it.fromInterface == null }
        public val functionOverrides: List<Function> get() = functions.filter { it.fromInterface != null }
    }

    /**
     * Extracts SDK generation data from a [ServerDefinition].
     *
     * This function analyzes the server definition and builds a hierarchical [Data]
     * structure containing all modules, endpoints, and their relationships. The
     * resulting data can be processed and used by [Format] implementations to
     * generate client SDK code.
     *
     * This step isn't strictly necessary when generating an SDK, but correctly
     * modeling the recursive tree structure of a server is difficult. This method
     * is provided to do this parsing for you.
     *
     * @param root The root module information (defaults to "Api")
     * @return A [Data] tree representing the entire API structure
     *
     * @sample
     * ```kotlin
     * val serverDef: ServerDefinition = myServer.build()
     * val apiData = serverDef.sdk(SdkModule.Info("MyApi"))
     * // Use apiData with SDK.Format implementations
     * ```
     */
    public fun ServerDefinition.sdk(root: SdkModule.Info = SdkModule.Info("Api")): Data {
        class Builder(val info: SdkModule.Info) {
            val endpoints = HashMap<Locationed<PathSpec0, InterfaceInfo>?, MutablePathSpecMap<ServerApiEndpoints>>()
            val modules = ArrayList<Locationed<PathSpec0, Builder>>()

            fun append(relativePath: PathSpec0, module: ServerDefinition) {
                endpoints
                    .getOrPut(
                        module.thisLayer.extensions[InterfaceInfo]?.let { Locationed(relativePath, it) },
                        ::MutablePathSpecMap
                    )
                    .apply {
                        module.thisLayer.endpoints.asSequence().forEach { entry ->
                            val api = ServerApiEndpoints(entry.value)
                            if (api.isNotEmpty()) put(relativePath + entry.location, api)
                        }
                    }

                for ((path, mod) in module.modules) {
                    var modRelPath = relativePath + path

                    val builder = module.thisLayer
                        .getModuleInfo(mod.thisLayer)
                        ?.let(::Builder)
                        ?.also {
                            modules.add(Locationed(modRelPath, it))
                            modRelPath = PathSpec.root
                        }
                        ?: this

                    builder.append(modRelPath, mod)
                }
            }

            fun build(): Data = Data(
                Data.Layer(
                    info,
                    endpoints.mapValues { it.value.toSealedPathSpecMap() }.toSealedMap()
                ),
                modules.mapItems { it.build() }.toSealedList()
            )
        }

        val root = Builder(root)

        root.append(PathSpec.root, this)

        return root.build()
    }


    private fun List<Locationed<PathSpec0, InterfaceInfo>>.filterSupertypes(): List<Locationed<PathSpec0, InterfaceInfo>> {
        val supertypes = flatMap { it.item.type.supertypes }.mapNotNull { it.classifier as? KClass<*> }
        return filter { it.item.type !in supertypes }
    }

    private fun Data.processToModules(path: PathSpec0): Module = Module(
        info = layer.info,
        path = path,
        extendsInterfaces = layer.endpoints.keys.filterNotNull().filterSupertypes(),
        functions = layer.endpoints.flatMap { (inter, endpoints) ->
            endpoints.flatMap { (path, endpoints) ->
                val websocket = endpoints.websocket?.let {
                    Function.Websocket(
                        handler = it,
                        path = path,
                        fromInterface = inter?.item,
                    )
                }

                val http = endpoints.http.map { (method, api) ->
                    Function.Endpoint(
                        handler = api,
                        endpoint = HttpEndpoint(path, method),
                        fromInterface = inter?.item,
                    )
                }

                http + listOfNotNull(websocket)
            }
        },
        children = children.map { (path, def) -> def.processToModules(path) },
    )

    /**
     * Processes raw API [Data] into a [Module] tree optimized for SDK generation.
     *
     * This is just a useful tool to parse api data into a more ergonomic format
     * for SDK generation. This transformation is lossy, but it retains and parses
     * the most commonly used aspects of SDK client api code.
     *
     * This function transforms the hierarchical [Data] structure into a more
     * convenient [Module] representation that:
     * - Flattens endpoints into function lists
     * - Resolves interface implementations
     * - Filters out interface supertypes
     * - Organizes child modules
     *
     * SDK [Format] implementations typically use this processed structure
     * to generate client code.
     *
     * @return The root [Module] of the processed API tree
     *
     * @sample
     * ```kotlin
     * val apiData = serverDef.sdk()
     * val module = apiData.processToModules()
     * // module.functions contains all API functions
     * // module.children contains nested modules
     * ```
     */
    public fun Data.processToModules(): Module = processToModules(PathSpec.root)

    /**
     * Exception thrown during SDK generation when an error occurs.
     *
     * This exception indicates problems during the SDK generation process,
     * such as missing annotations, unsupported types, or configuration errors.
     *
     * @property message A description of what went wrong during generation
     */
    public open class GenerationException(override val message: String) : Exception()

    private class Runtime(server: ServerBuilder) : ServerRuntimeBase(server.build()) {
        override val serverId: String = "SDK Runtime"
        override val serverVersion: String = "0.0.0"

        override suspend fun <T> Task<T>.invoke(input: T): Nothing =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")

        override suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>): Nothing =
            throw NotImplementedError("SDK Runner only exists to retrieve serialization information")
    }

    /**
     * Convenience method to generate SDK files using default server settings.
     *
     * This method creates a temporary [ServerRuntime] from the [ServerBuilder],
     * initializes all settings with their default values, and then calls the
     * [Format.write] method to generate the SDK.
     *
     * @param server The server builder containing the API definition
     * @param archive The archive where SDK files will be written
     */
    @OptIn(ExperimentalLightningServer::class)
    public fun Format.writeUsingDefaultSettings(server: ServerBuilder, archive: Archive) {
        with(Runtime(server)) {
            settings.readyUsingDefaults()
            write(archive)
        }
    }

    /**
     * Convenience method to generate SDK files using default server settings.
     *
     * This method creates a temporary [ServerRuntime] from the [ServerBuilder],
     * initializes all settings with their default values, and then calls the
     * [Format.write] method to generate the SDK.
     *
     * This is a safe wrapper around the base method `writeUsingDefaultSettings(Archive)`.
     * This method is guaranteed to remain stable in future versions.
     *
     * @param server The server builder containing the API definition
     * @param folder The folder where SDK files will be written
     */
    @OptIn(ExperimentalLightningServer::class)
    public fun Format.writeUsingDefaultSettings(server: ServerBuilder, folder: KFile): Unit =
        writeUsingDefaultSettings(server, Archive.folder(folder))

    /**
     * Writes the SDK files to the provided folder.
     *
     * This method generates all necessary SDK files (type definitions, interfaces, implementations, etc.).
     *
     * This is a safe wrapper around the base method `write(Archive)`. This method is guaranteed to remain
     * stable in future versions.
     * */
    @OptIn(ExperimentalLightningServer::class)
    context(server: ServerRuntime)
    public fun Format.write(folder: KFile): Unit = write(Archive.folder(folder))
}