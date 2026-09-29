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

    private fun String.isCleanup(): Boolean = contains("rm -f") || contains("Remove-Item")

    @Test
    @Ignore("Feature to be added later")
    fun settingsNeverLandOnDiskInPlaintext() {
        val d = Deployment()
        d.write()
        // The plaintext settings file resource is gone entirely.
        assertFalse(
            d.terraformRoot.resourceTypes().contains("local_sensitive_file"),
            "local_sensitive_file must not be emitted; it wrote settings to disk in cleartext",
        )
        // The only surviving mentions of the old plaintext paths are the commands that delete them.
        d.terraformRoot.allStrings().filter { it.contains("raw-settings.json") }.forEach {
            assertTrue(it.isCleanup(), "raw-settings.json referenced outside a cleanup command: $it")
        }

        val steps = d.terraformRoot.findResource("null_resource", "lambda_jar_source")!!.localExecSteps()
        // Settings reach the shell through the environment, not a tracked file.
        assertTrue(
            steps.any { it["environment"]?.jsonObject?.containsKey("SETTINGS_JSON") == true },
            "the encryption chain must pass the settings via a SETTINGS_JSON environment variable",
        )
        // ...and the scratch file openssl reads is shredded in the same chain.
        assertTrue(
            steps.any { it["command"]!!.jsonPrimitive.content.let { c -> c.isCleanup() && c.contains("build/settings-plain.json") } },
            "the encryption chain must remove build/settings-plain.json after encrypting",
        )
    }

    @Test
    @Ignore("Feature to be added later")
    fun settingsRereadDiscardsDecryptedOutput() {
        val d = Deployment()
        d.write()
        val command = d.terraformRoot.findResource("null_resource", "settings_reread")!!
            .localExecSteps().single()["command"]!!.jsonPrimitive.content
        // The decryption is a proof the bundle opens, not a way to get the plaintext back.
        assertContains(command, "/dev/null")
        assertContains(command, "NUL")
        assertFalse(command.contains(".decrypted.json"), "settings_reread must not write a decrypted dump: $command")
    }

    /**
     * The shred must live in the *same* command as the encryption.  Terraform abandons the rest of a
     * provisioner chain as soon as one step fails, so splitting them back into separate steps would
     * silently reintroduce the leak on exactly the openssl failure that strands the plaintext.
     */
    @Test
    @Ignore("Feature to be added later")
    fun shredShareIsAtomicWithEncryption() {
        val d = Deployment()
        d.write()
        val encryptSteps = d.terraformRoot.findResource("null_resource", "lambda_jar_source")!!.localExecSteps()
            .map { it["command"]!!.jsonPrimitive.content }
            .filter { it.contains("openssl enc -aes-256-cbc") }
        assertTrue(encryptSteps.isNotEmpty(), "expected an encryption step")
        encryptSteps.forEach {
            assertTrue(
                it.contains("trap ") && it.contains("finally"),
                "the encrypting command must delete the plaintext on every exit path: $it",
            )
        }
    }

}
