// by Claude
package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.sdk.SDK.processToModules
import com.lightningkite.lightningserver.typed.sdk.SDK.sdk
import com.lightningkite.lightningserver.typed.sdk.SDK.writeUsingDefaultSettings
import com.lightningkite.lightningserver.typed.sdk.SdkModule.Companion.withSdkInfo
import com.lightningkite.services.kfile.KFile
import kotlin.test.*

/**
 * Tests for SDK generation utilities, casing functions, and module processing.
 */
class SdkGenerationTest {

    // ========== Casing Utility Tests ==========

    @Test
    fun titleCase_converts_camelCase_to_title_case() {
        assertEquals("Hello World", "helloWorld".titleCase())
        assertEquals("My Function Name", "myFunctionName".titleCase())
        assertEquals("A", "a".titleCase())
    }

    @Test
    fun titleCase_handles_uppercase_sequences() {
        // Uppercase sequences are treated as single words
        assertEquals("HTTPRequest", "HTTPRequest".titleCase())
        assertEquals("XMLParser", "XMLParser".titleCase())
        assertEquals("Get APIResponse", "getAPIResponse".titleCase())
    }

    @Test
    fun camelCase_converts_from_various_formats() {
        assertEquals("helloWorld", "hello_world".camelCase())
        assertEquals("helloWorld", "hello-world".camelCase())
        assertEquals("helloWorld", "Hello World".camelCase())
        // SCREAMING_CASE preserves internal case
        assertEquals("hELLOWORLD", "HELLO_WORLD".camelCase())
    }

    @Test
    fun pascalCase_converts_to_PascalCase() {
        assertEquals("HelloWorld", "hello_world".pascalCase())
        assertEquals("HelloWorld", "hello-world".pascalCase())
        assertEquals("HelloWorld", "helloWorld".pascalCase())
        assertEquals("MyClassName", "my_class_name".pascalCase())
    }

    @Test
    fun kabobCase_converts_to_kabob_case() {
        assertEquals("hello-world", "helloWorld".kabobCase())
        assertEquals("hello-world", "HelloWorld".kabobCase())
        assertEquals("my-function-name", "myFunctionName".kabobCase())
    }

    @Test
    fun snakeCase_converts_to_snake_case() {
        assertEquals("hello_world", "helloWorld".snakeCase())
        assertEquals("hello_world", "HelloWorld".snakeCase())
        assertEquals("my_variable_name", "myVariableName".snakeCase())
    }

    @Test
    fun screamingSnakeCase_converts_to_SCREAMING_SNAKE_CASE() {
        assertEquals("HELLO_WORLD", "helloWorld".screamingSnakeCase())
        assertEquals("MY_CONSTANT", "myConstant".screamingSnakeCase())
    }

    @Test
    fun functionCase_handles_special_characters() {
        assertEquals("helloWorld", "hello world".functionCase())
        assertEquals("myFunction", "my-function".functionCase())
        assertEquals("getValue", "get_value".functionCase())
    }

    @Test
    fun functionCase_filters_invalid_characters() {
        // Special characters are removed but no case boundary is created
        assertEquals("helloworld", "hello!@#world".functionCase())
        // $ is filtered, leaving "testfunction"
        val result = "test\$function".functionCase()
        assertTrue(
            result.contains("test") && result.contains("function"),
            "Should contain test and function, got: $result"
        )
    }

    @Test
    fun functionCase_drops_leading_digits() {
        assertEquals("myFunction", "123myFunction".functionCase())
        // Leading digits are dropped, underscore becomes word boundary
        assertEquals("_test", "456_test".functionCase())
    }

    @Test
    fun spaceCase_converts_to_space_separated() {
        // spaceCase decapitalizes first char but preserves subsequent caps
        assertEquals("hello World", "helloWorld".spaceCase())
        assertEquals("my Function", "myFunction".spaceCase())
    }

    // ========== SdkModule.Info Tests ==========

    @Test
    fun sdkModuleInfo_valueName_defaults_to_camelCase() {
        val info = SdkModule.Info("UserApi")
        assertEquals("UserApi", info.interfaceName)
        assertEquals("userApi", info.valueName)
    }

    @Test
    fun sdkModuleInfo_allows_custom_valueName() {
        val info = SdkModule.Info("UserApi", "users")
        assertEquals("UserApi", info.interfaceName)
        assertEquals("users", info.valueName)
    }

    // ========== SDK Data Extraction Tests ==========

    object SimpleServer : ServerBuilder() {
        val root = path.get bind ApiHttpHandler(
            summary = "Get Root",
            auth = noAuth,
            implementation = { _: Unit -> "Hello" }
        )

        val action = path.path("action").post bind ApiHttpHandler(
            summary = "Do Action",
            auth = noAuth,
            implementation = { input: String -> input.uppercase() }
        )
    }

    @Test
    fun sdk_extracts_data_from_server_definition() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))

            assertNotNull(data)
            assertEquals("TestApi", data.layer.info.interfaceName)
        }
    }

    @Test
    fun sdk_data_contains_endpoints() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))

            // Should have endpoints
            assertTrue(data.layer.endpoints.isNotEmpty())
        }
    }

    @Test
    fun processToModules_creates_module_structure() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))
            val module = data.processToModules()

            assertEquals("TestApi", module.info.interfaceName)
            assertTrue(module.functions.isNotEmpty())
        }
    }

    @Test
    fun processToModules_extracts_function_names() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))
            val module = data.processToModules()

            val functionNames = module.functions.map { it.functionName }
            assertTrue(
                functionNames.contains("getRoot") || functionNames.contains("doAction"),
                "Should contain expected function names, got: $functionNames"
            )
        }
    }

    // ========== Nested Module Tests ==========

    object ChildModule : ServerBuilder() {
        val childAction = path.path("child-action").get bind ApiHttpHandler(
            summary = "Child Action",
            auth = noAuth,
            implementation = { _: Unit -> 42 }
        )
    }

    object ParentServer : ServerBuilder() {
        val parentRoot = path.get bind ApiHttpHandler(
            summary = "Parent Root",
            auth = noAuth,
            implementation = { _: Unit -> "parent" }
        )

        val child = path.path("child") module ChildModule.withSdkInfo("ChildApi", "child")
    }

    @Test
    fun sdk_extracts_nested_modules() {
        ParentServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("ParentApi"))

            // Check for child modules
            assertTrue(data.children.isNotEmpty(), "Should have child modules")
        }
    }

    @Test
    fun processToModules_includes_child_modules() {
        ParentServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("ParentApi"))
            val module = data.processToModules()

            assertTrue(module.children.isNotEmpty(), "Processed module should have children")

            val childModule = module.children.firstOrNull()
            assertNotNull(childModule)
            assertEquals("ChildApi", childModule.info.interfaceName)
        }
    }

    // ========== SDK Function Tests ==========

    @Test
    fun sdk_function_endpoint_has_correct_properties() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))
            val module = data.processToModules()

            val endpoint = module.functions.filterIsInstance<SDK.Function.Endpoint>().first()

            assertNotNull(endpoint.handler)
            assertNotNull(endpoint.endpoint)
            assertNotNull(endpoint.summary)
        }
    }

    @Test
    fun sdk_function_arguments_extracted_correctly() {
        SimpleServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("TestApi"))
            val module = data.processToModules()

            // The POST endpoint has String input
            val postEndpoint = module.functions
                .filterIsInstance<SDK.Function.Endpoint>()
                .find { it.functionName == "doAction" }

            assertNotNull(postEndpoint, "Should find doAction endpoint")

            // Should have input argument
            val hasInput = postEndpoint.arguments.any { it.name == "input" }
            assertTrue(hasInput, "POST endpoint should have input argument")
        }
    }

    // ========== SDK Data Sequence Tests ==========

    @Test
    fun sdk_data_asSequence_returns_all_nodes() {
        ParentServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("ParentApi"))
            val nodes = data.asSequence().toList()

            // Should have at least parent and child
            assertTrue(nodes.size >= 2, "Should have multiple nodes, got ${nodes.size}")

            // First node is root with depth 0
            assertEquals(0, nodes.first().depth)
        }
    }

    @Test
    fun sdk_data_node_tracks_ancestors() {
        ParentServer.test({}) {
            val data = serverRuntime.server.sdk(SdkModule.Info("ParentApi"))
            val nodes = data.asSequence().toList()

            val rootNode = nodes.first()
            assertTrue(rootNode.ancestors.isEmpty(), "Root should have no ancestors")

            // Child nodes should have ancestors
            val childNodes = nodes.filter { it.depth > 0 }
            childNodes.forEach { child ->
                assertEquals(child.depth, child.ancestors.size, "Ancestor count should match depth")
            }
        }
    }

    // ========== withSdkInfo Tests ==========

    @Test
    fun withSdkInfo_creates_SdkModule_with_info() {
        val module = ChildModule.withSdkInfo("CustomApi", "custom")

        assertEquals(ChildModule, module.value)
        assertEquals("CustomApi", module.info.interfaceName)
        assertEquals("custom", module.info.valueName)
    }

    @Test
    fun withSdkInfo_infers_name_from_class() {
        // Named module classes get their names inferred
        // The "Module" suffix is removed before adding "Api"
        val module = ChildModule.withSdkInfo()

        assertEquals("ChildApi", module.info.interfaceName)
        assertEquals("child", module.info.valueName)
    }

    // ========== FetcherSdk Structure Tests ==========

    @Test
    fun fetcherSdk_multipleFiles_structure_has_correct_filenames() {
        val sdk = FetcherSdk(
            packageName = "com.example",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.MultipleFiles(
                interfaceFilename = "TestApi.kt",
                liveFilename = "LiveTestApi.kt"
            )
        )

        val structure = sdk.fileStructure as FetcherSdk.Structure.MultipleFiles
        assertEquals("TestApi.kt", structure.interfaceFilename)
        assertEquals("LiveTestApi.kt", structure.liveFilename)
    }

    @Test
    fun fetcherSdk_singleFile_structure_has_correct_filename() {
        val sdk = FetcherSdk(
            packageName = "com.example",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.SingleFile("api.kt")
        )

        val structure = sdk.fileStructure as FetcherSdk.Structure.SingleFile
        assertEquals("api.kt", structure.filename)
    }

    // ========== SDK Generation Output Tests ==========

    @Test
    fun fetcherSdk_generates_to_archive() {
        // Use the existing test folder pattern
        val folder = KFile("./build/test-sdk-output")

        val sdk = FetcherSdk(
            packageName = "com.example.test",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.SingleFile("sdk.kt")
        )

        // Should not throw
        sdk.writeUsingDefaultSettings(SimpleServer, folder)
    }

    @Test
    fun fetcherSdk_multiFile_generates_to_archive() {
        val folder = KFile("./build/test-sdk-multi")

        val sdk = FetcherSdk(
            packageName = "com.example.test",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.MultipleFiles(
                interfaceFilename = "TestApi.kt",
                liveFilename = "LiveTestApi.kt"
            )
        )

        // Should not throw
        sdk.writeUsingDefaultSettings(SimpleServer, folder)
    }

    // ========== TypescriptFetcherSdk Tests ==========

    @Test
    fun typescriptSdk_generates_to_archive() {
        val folder = KFile("./build/test-ts-sdk")

        val sdk = TypescriptFetcherSdk(
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = TypescriptFetcherSdk.Structure.SingleFile("sdk.ts")
        )

        // Should not throw
        sdk.writeUsingDefaultSettings(SimpleServer, folder)
    }

    @Test
    fun typescriptSdk_multiFile_generates_to_archive() {
        val folder = KFile("./build/test-ts-multi")

        val sdk = TypescriptFetcherSdk(
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = TypescriptFetcherSdk.Structure.MultipleFiles(
                modelsFilename = "models.ts",
                interfaceFilename = "TestApi.ts",
                liveFilename = "LiveTestApi.ts"
            )
        )

        // Should not throw
        sdk.writeUsingDefaultSettings(SimpleServer, folder)
    }

    // ========== SDK Module Name Uniqueness Tests ==========

    object DuplicateNameServer : ServerBuilder() {
        val root = path.get bind ApiHttpHandler(
            summary = "Get Item",  // Would produce "getItem"
            auth = noAuth,
            implementation = { _: Unit -> 1 }
        )

        val getItem = path.path("item").get bind ApiHttpHandler(
            summary = "Get Item",  // Same name - should be made unique
            auth = noAuth,
            implementation = { _: Unit -> 2 }
        )
    }

    @Test
    fun sdk_handles_duplicate_function_names() {
        // This tests that SDK generation handles duplicate function names
        // without crashing - the ensureUniqueNames() function should handle this
        val folder = KFile("./build/test-sdk-unique")

        val sdk = FetcherSdk(
            packageName = "com.example.test",
            rootInfo = SdkModule.Info("TestApi"),
            fileStructure = FetcherSdk.Structure.SingleFile("sdk.kt")
        )

        // Should not throw
        sdk.writeUsingDefaultSettings(DuplicateNameServer, folder)
    }

    // ========== Edge Cases ==========

    @Test
    fun empty_string_casing_functions_dont_crash() {
        assertEquals("", "".titleCase())
        assertEquals("", "".camelCase())
        assertEquals("", "".pascalCase())
        assertEquals("", "".snakeCase())
        assertEquals("", "".kabobCase())
    }

    @Test
    fun single_character_casing_works() {
        assertEquals("A", "a".titleCase())
        assertEquals("a", "A".camelCase())
        assertEquals("A", "a".pascalCase())
        assertEquals("a", "a".snakeCase())
        assertEquals("a", "a".kabobCase())
    }

    @Test
    fun functionCase_with_only_digits_returns_empty() {
        // Function names can't start with digits, so if all are digits, result is empty
        assertEquals("", "12345".functionCase())
    }
}
