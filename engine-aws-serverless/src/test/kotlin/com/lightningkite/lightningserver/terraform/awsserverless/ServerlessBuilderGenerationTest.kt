package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.awsserverless.AwsAdapter
import com.lightningkite.lightningserver.engine.awsserverless.TestAwsAdapter
import com.lightningkite.services.data.toEmailAddress
import com.lightningkite.services.terraform.AwsVpc
import kotlinx.serialization.json.*
import software.amazon.awssdk.regions.Region
import java.io.File
import kotlin.reflect.KClass
import kotlin.test.*

/**
 * Generation-level tests for the serverless builder: write a deployment to a temporary directory
 * and assert the settings bundle never lands on disk as plaintext.
 *
 * These do not call AWS; they only exercise the Kotlin -> Terraform JSON generation.
 */
class ServerlessBuilderGenerationTest {

    object TestServer : ServerBuilder()

    private val tmpRoot = File("build/test-terraform")

    inner class Deployment : TerraformAwsServerlessDomainBuilder<TestServer>(TestServer) {
        override val handler: KClass<out AwsAdapter> = TestAwsAdapter::class
        override val storageBucket = "test-tf-state"
        override val region: Region = Region.US_WEST_2
        override val displayName = "Serverless Test"
        override val domainZone = "example.com"
        override val domain = "serverless.example.com"
        override val debug = true
        override val emergencyContact = "ops@example.com".toEmailAddress()
        override val applicationVpc: AwsVpc = AwsVpc.None
        override val terraformRoot = File(tmpRoot, "serverless")

        /** Fulfills the non-optional global settings so [write] passes without real services. */
        override fun TestServer.settings() {
            fulfillSetting(secretBasis.name, Json.encodeToJsonElement(secretBasis.serializer, secretBasis.default))
            fulfillSetting(loggingSettings.name, Json.encodeToJsonElement(loggingSettings.serializer, loggingSettings.default))
            fulfillSetting(telemetrySettings.name, JsonNull)
        }
    }

    /** Finds a single resource block (`resource.<type>.<name>`) across all generated files. */
    private fun File.findResource(type: String, name: String): JsonObject? {
        listFiles { f -> f.name.endsWith(".tf.json") }?.forEach { file ->
            (Json.parseToJsonElement(file.readText()).jsonObject["resource"] as? JsonObject)
                ?.get(type)?.jsonObject?.get(name)?.let { return it.jsonObject }
        }
        return null
    }

    /** Returns the set of resource type names present across all generated files. */
    private fun File.resourceTypes(): Set<String> = buildSet {
        listFiles { f -> f.name.endsWith(".tf.json") }?.forEach { file ->
            (Json.parseToJsonElement(file.readText()).jsonObject["resource"] as? JsonObject)
                ?.keys?.let { addAll(it) }
        }
    }

    /** Every string literal emitted across all generated files, for leak sweeps. */
    private fun File.allStrings(): List<String> = buildList {
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.values.forEach(::walk)
                is JsonArray -> element.forEach(::walk)
                is JsonPrimitive -> if (element.isString) add(element.content)
            }
        }
        listFiles { f -> f.name.endsWith(".tf.json") }?.forEach { walk(Json.parseToJsonElement(it.readText())) }
    }

    /** The ordered `local-exec` steps of a `null_resource`, whether one block or many. */
    private fun JsonObject.localExecSteps(): List<JsonObject> =
        this["provisioner"]!!.jsonObject["local-exec"]!!.let { it as? JsonArray ?: JsonArray(listOf(it)) }
            .map { it.jsonObject }

    @Test
    fun settingsNeverLandOnDiskInPlaintext() {
        val d = Deployment()
        d.write()
        // The plaintext settings file resource is gone entirely.
        assertFalse(
            d.terraformRoot.resourceTypes().contains("local_sensitive_file"),
            "local_sensitive_file must not be emitted; it wrote settings to disk in cleartext",
        )
        d.terraformRoot.allStrings().filter { it.contains("raw-settings.json") }.forEach {
            fail("raw-settings.json must not be referenced: $it")
        }

        val encrypt = d.terraformRoot.findResource("null_resource", "lambda_jar_source")!!.localExecSteps()
            .single { it["command"]!!.jsonPrimitive.content.contains("openssl enc -aes-256-cbc") }
        // Settings reach openssl through the environment and a pipe, never a file.
        assertEquals(
            "\${jsonencode(local.settings_raw)}",
            encrypt["environment"]!!.jsonObject["SETTINGS_JSON"]!!.jsonPrimitive.content,
        )
        val command = encrypt["command"]!!.jsonPrimitive.content
        assertContains(command, "\$env:SETTINGS_JSON | openssl enc")
        assertContains(command, "printf '%s' \\\"\$SETTINGS_JSON\\\" | openssl enc")
        assertFalse(command.contains(" -in "), "the encryption must read the settings from stdin: $command")
    }

    /** The package is rebuilt, and so re-encrypted, whenever the settings change. */
    @Test
    fun settingsChangesRetriggerEncryption() {
        val d = Deployment()
        d.write()
        val jar = d.terraformRoot.findResource("null_resource", "lambda_jar_source")!!["triggers"]!!.jsonObject
        assertEquals("\${sha256(jsonencode(local.settings_raw))}", jar["settingsHash"]!!.jsonPrimitive.content)
    }

    /**
     * A missing build directory (fresh checkout, or deleted) must rebuild the package: the marker file
     * creates build/ for the copy, and recreating it changes the package trigger, which defers the
     * archive read until the package exists.
     */
    @Test
    fun missingBuildDirectoryRebuildsPackage() {
        val d = Deployment()
        d.write()
        val marker = d.terraformRoot.findResource("local_file", "lambda_package_marker")!!
        assertEquals("\${path.module}/build/.lambda-package", marker["filename"]!!.jsonPrimitive.content)
        val jar = d.terraformRoot.findResource("null_resource", "lambda_jar_source")!!["triggers"]!!.jsonObject
        assertEquals("\${local_file.lambda_package_marker.id}", jar["packageMarker"]!!.jsonPrimitive.content)
        // The marker sits beside the package, not inside it: the package step wipes build/lambda.
        val archive = d.terraformRoot.listFiles { f -> f.name.endsWith(".tf.json") }!!.firstNotNullOf {
            (Json.parseToJsonElement(it.readText()).jsonObject["data"] as? JsonObject)?.get("archive_file")?.jsonObject?.get("lambda")?.jsonObject
        }
        assertEquals("\${path.module}/build/lambda", archive["source_dir"]!!.jsonPrimitive.content)
    }

}
