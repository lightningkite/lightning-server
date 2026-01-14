package com.lightningkite.lightningserver.terraform

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.services.terraform.*
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*
import java.io.File
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Base class for generating Terraform configuration for Lightning Server deployments.
 *
 * This abstract class coordinates the generation of Terraform JSON files from a ServerBuilder,
 * managing settings, variables, providers, and secrets. It implements the TerraformEmitter
 * interface to allow service implementations to contribute their own Terraform configuration.
 *
 * Typical usage:
 * 1. Extend this class with your deployment configuration
 * 2. Implement abstract properties: [builder], [secretsSource], [terraformRoot]
 * 3. Implement [settings] to configure your server's services
 * 4. Call [write] to generate Terraform JSON files
 * 5. Call [deploy] to execute terraform init/plan/apply workflow
 *
 * @param S The ServerBuilder type being deployed
 */
public abstract class BaseTerraformEmitter<S : ServerBuilder> : TerraformEmitter {
    /** The ServerBuilder instance to generate Terraform configuration for. */
    protected abstract val builder: S

    /** Source for retrieving and storing secrets (AWS credentials, API keys, etc.). */
    public abstract val secretsSource: SecretSource

    /** Directory where generated Terraform JSON files will be written. */
    public abstract val terraformRoot: File

    /**
     * Extension function to configure server settings for deployment.
     * This is where you specify database, cache, file storage, and other service settings.
     */
    public abstract fun S.settings()

    /**
     * Use Tofu instead of Terraform to run your infrastructure setup.
     */
    public open val useTofu: Boolean get() = false

    /**
     * Additional settings beyond the server's built-in settings.
     * Override to include extra configuration needed for deployment.
     */
    protected open val additionalSettings: Set<ServerSetting<*, *>> get() = setOf()

    /** Set of provider imports required by this deployment (e.g., aws, random, mongodbatlas). */
    protected val terraformProviderImports: MutableSet<TerraformProviderImport> = HashSet()

    /**
     * Register a Terraform provider import requirement.
     * Called by service implementations to declare needed providers.
     */
    override fun require(provider: TerraformProviderImport) {
        terraformProviderImports += provider
    }

    /** Set of provider configurations required by this deployment. */
    protected val terraformProviders: MutableSet<TerraformProvider> = HashSet()

    /**
     * Register a Terraform provider configuration.
     * Called by service implementations to configure providers with specific settings.
     */
    override fun require(provider: TerraformProvider) {
        terraformProviders += provider
    }

    /** Map of setting names to their resolved JSON values. */
    protected val settings: MutableMap<String, JsonElement> = HashMap()

    /**
     * Fulfill a server setting with a concrete value.
     * Called by service implementations to provide values for their settings.
     */
    override fun fulfillSetting(settingName: String, element: JsonElement) {
        settings[settingName] = element
    }

    /** List of variables that need to be provided externally (e.g., via environment or secrets). */
    protected val variables: MutableList<TerraformNeed<*>> = ArrayList()

    /**
     * Register a variable that needs to be provided at terraform apply time.
     * Called by service implementations to declare needed variables.
     */
    override fun variable(need: TerraformNeed<*>) {
        variables += need
    }

    /** Map of context names to Terraform JSON objects. Each will become a separate .tf.json file. */
    protected val files: MutableMap<String, TerraformJsonObject> = mutableMapOf()
    protected val extraFiles: MutableMap<String, String> = mutableMapOf()

    /**
     * Emit Terraform configuration into a specific context (file).
     * If context is null, configuration goes into "unclassified.tf.json".
     *
     * @param context Optional name for the terraform file (without .tf.json extension)
     * @param action Builder lambda to construct Terraform JSON objects
     */
    override fun emit(
        context: String?,
        action: TerraformJsonObject.() -> Unit,
    ) {
        files.getOrPut(context ?: "unclassified") { TerraformJsonObject() }.action()
    }

    /**
     * Emit Terraform configuration into a specific context (file).
     * If context is null, configuration goes into "unclassified.tf.json".
     *
     * @param context Optional name for the terraform file (without .tf.json extension)
     * @param action Builder lambda to construct Terraform JSON objects
     */
    public fun emitExtra(
        context: String,
        content: String,
    ) {
        extraFiles.getOrPut(context) { content }
    }

    /**
     * Finalizes the Terraform configuration by ensuring all required settings have been fulfilled.
     * Called automatically by [write] before generating files.
     *
     * @throws IllegalStateException if any required settings are missing
     */
    protected open fun finalize() {
        builder.settings()
        val built = builder.build()
        ServerSettings(built)   // run checks for conflicts & circular references
        val required = built.settings.filter { !it.optional }.map { it.name }.toSet() +
                additionalSettings.filter { !it.optional }.map { it.name }
        val missing = required - settings.keys - built.settingOverrides.keys.map { it.name }.toSet()
        if (missing.isNotEmpty()) throw IllegalStateException("Missing settings for deployment ${projectPrefix}: $missing")
    }

    private var finalized = false

    /**
     * Generates and writes Terraform JSON files to [terraformRoot].
     * Automatically calls [finalize] on first invocation to validate configuration.
     *
     * **Important**: This will delete any existing .tf.json files in the terraformRoot directory
     * before writing new ones, ensuring a clean slate for each generation.
     */
    public fun write() {
        if (!finalized) {
            finalize()
            finalized = true
        }
        terraformRoot.mkdirs()
        val prettyJson = Json { prettyPrint = true }
        terraformRoot.listFiles()?.filter { it.name.endsWith(".tf.json") }?.forEach { it.delete() }
        for ((name, content) in files.entries) {
            terraformRoot.resolve("$name.tf.json").writeText(prettyJson.encodeToString(content.toJsonObject()))
        }
        for((name, content) in extraFiles){
            terraformRoot.resolve(name).writeText(content)
        }
    }

    public companion object {
        /** AWS access key ID for authenticating with AWS services. */
        public val AWS_ACCESS_KEY_ID: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_ACCESS_KEY_ID"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "Generated in the AWS Console."
        }

        /** AWS secret access key for authenticating with AWS services. */
        public val AWS_SECRET_ACCESS_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_SECRET_ACCESS_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "Generated in the AWS Console."
        }

        /**
         * AWS profile name from local AWS CLI configuration.
         * Alternative to using access key ID and secret access key directly.
         */
        public val AWS_PROFILE: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_PROFILE"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String =
                "The name of the AWS profile (from the current machine) that should be used for authenticating AWS."
        }

        /**
         * Customer-provided encryption key for AWS S3 Server-Side Encryption.
         * Automatically generated if not provided. Must be a 256-bit key encoded in base64.
         */
        public val AWS_SSE_CUSTOMER_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_SSE_CUSTOMER_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String
                get() = ByteArray(256 / 8).apply {
                    SecureRandom.getInstanceStrong().nextBytes(this)
                }.let { Base64.encode(it) }
            override val instructions: String =
                "This is the base64-encoded value of the key, which must decode to 256 bits."
        }

        /** MongoDB Atlas public API key for managing MongoDB resources via Terraform. */
        public val MONGODB_ATLAS_PUBLIC_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "MONGODB_ATLAS_PUBLIC_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "You can obtain this by making a key in the MongoDB Atlas console."
        }

        /** MongoDB Atlas private API key for managing MongoDB resources via Terraform. */
        public val MONGODB_ATLAS_PRIVATE_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "MONGODB_ATLAS_PRIVATE_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "You can obtain this by making a key in the MongoDB Atlas console."
        }
    }

    /**
     * Variables that are handled specially (via environment variables) rather than as Terraform variables.
     * These are called "pseudo" variables because they're not declared as Terraform variables but are still needed.
     */
    public val pseudoVariables: List<TerraformNeed<*>> = listOf(
        AWS_ACCESS_KEY_ID,
        AWS_SECRET_ACCESS_KEY,
        AWS_PROFILE,
        AWS_SSE_CUSTOMER_KEY,
        MONGODB_ATLAS_PUBLIC_KEY,
        MONGODB_ATLAS_PRIVATE_KEY,
    )

    /**
     * Prepares the environment for running Terraform commands.
     * Calls [write] to generate Terraform files, then collects all necessary credentials
     * and variables into environment variables for Terraform.
     *
     * **Credential Resolution Order**:
     * 1. Check environment variables (AWS_ACCESS_KEY_ID, AWS_PROFILE)
     * 2. Check [secretsSource]
     * 3. Prompt user interactively if not found
     *
     * @return Map of environment variables to pass to terraform commands
     */
    public open fun prepareTerraform(): Map<String, String> {
        write()

        val env = HashMap<String, String>()
        System.getenv("AWS_ACCESS_KEY_ID")?.let {
            env["AWS_ACCESS_KEY_ID"] = it
            env["AWS_SECRET_ACCESS_KEY"] = System.getenv("AWS_SECRET_ACCESS_KEY")
        } ?: System.getenv("AWS_PROFILE")?.let {
            val existing = secretsSource.getOrNull(AWS_PROFILE)
            if (existing != it) throw IllegalStateException("Secret and provided profile are different; this seems dangerous!")
            env["AWS_PROFILE"] = it
        } ?: secretsSource.getOrNull(AWS_ACCESS_KEY_ID)?.let {
            env["AWS_ACCESS_KEY_ID"] = it
            env["AWS_SECRET_ACCESS_KEY"] = secretsSource.get(AWS_SECRET_ACCESS_KEY)
        } ?: secretsSource.getOrNull(AWS_PROFILE)?.let {
            env["AWS_PROFILE"] = it
        } ?: run {
            println("AWS Credentials aren't set up.  Do you want to use a profile (P) or direct access key (D)?")
            readInput {
                when (it.singleOrNull()?.uppercaseChar()) {
                    null -> throw IllegalArgumentException("Please enter either D or P")
                    'D' -> {
                        env["AWS_ACCESS_KEY_ID"] = secretsSource.get(AWS_ACCESS_KEY_ID)
                        env["AWS_SECRET_ACCESS_KEY"] = secretsSource.get(AWS_SECRET_ACCESS_KEY)
                    }

                    'P' -> {
                        env["AWS_PROFILE"] = secretsSource.get(AWS_PROFILE)
                    }
                }
            }
        }

        terraformRoot.resolve("main.tf.json").takeIf { it.exists() }?.let { Json.parseToJsonElement(it.readText()) }
            ?.let {
                val usesEncryption = it.jsonObject["terraform"]
                    ?.jsonObject?.get("backend")
                    ?.jsonObject?.get("s3")
                    ?.jsonObject?.get("encrypt")
                    ?.jsonPrimitive?.booleanOrNull == true
                if (usesEncryption)
                    env["AWS_SSE_CUSTOMER_KEY"] = secretsSource.get(AWS_SSE_CUSTOMER_KEY)
            }

        // Patch for MongoDB
        if (terraformProviders.any { it.import.name == "mongodbatlas" }) {
            env["MONGODB_ATLAS_PUBLIC_KEY"] = secretsSource.get(MONGODB_ATLAS_PUBLIC_KEY)
            env["MONGODB_ATLAS_PRIVATE_KEY"] = secretsSource.get(MONGODB_ATLAS_PRIVATE_KEY)
        }

        variables.forEach { need ->
            @Suppress("UNCHECKED_CAST")
            val serializer = need.serializer as KSerializer<Any?>
            fun SerialDescriptor.isActuallyPrimitive(): Boolean =
                kind is PrimitiveKind || isInline && getElementDescriptor(0).isActuallyPrimitive()
            if (serializer.descriptor.isActuallyPrimitive()) {
                env["TF_VAR_${need.name}"] =
                    Json.encodeToJsonElement(serializer, secretsSource.get(need)).jsonPrimitive.content
            } else {
                env["TF_VAR_${need.name}"] = Json.encodeToString(serializer, secretsSource.get(need))
            }
        }

        return env
    }

    private val terraformEnv by lazy { prepareTerraform() }

    /**
     * Executes a terraform command in the [terraformRoot] directory with proper environment variables.
     *
     * @param command The terraform command and arguments to execute
     * @throws Exception if terraform exits with a non-zero exit code
     */
    public fun terraform(vararg command: String) {
        terraformRoot.runTerraform(if (useTofu) "tofu" else "terraform", terraformEnv, *command)
    }

    /**
     * Performs a complete deployment workflow:
     * 1. `terraform init` - Initialize Terraform and download providers
     * 2. `terraform plan` - Show planned changes
     * 3. Wait for user confirmation (press enter)
     * 4. `terraform apply` - Apply the changes to cloud infrastructure
     *
     * This is the primary method for deploying your server to cloud infrastructure.
     */
    public open fun deploy(autoApprove: Boolean = false) {
        println("Initializing...")
        terraform("init", "-upgrade", "-input=false", "-no-color")
        println("Planning...")
        terraform("plan", "-input=false", "-no-color", "-out=plan.tfplan")
        if (!autoApprove) {
            println("Press enter to continue with plan...")
            readln()
        }
        println("Applying...")
        terraform("apply", "-input=false", "-auto-approve", "-no-color", "plan.tfplan")
        println("Deployed!")
    }

    public open fun destroy() {
        println("Initializing...")
        terraform("init", "-upgrade", "-input=false", "-no-color")
        println("/!\\ STOP! /!\\")
        println("You are about to destroy all of the infrastructure related to the ${projectPrefix} deployment.")
        println("If you are SURE you want to proceed, please enter 'destroy ${projectPrefix}'.")
        if (readLine() != "destroy $projectPrefix") {
            println("That didn't match.  Bailing out...")
            return
        }
        println("Destroying...")
        terraform("destroy", "-input=false", "-auto-approve", "-no-color", "plan.tfplan")
        println("Deployed!")
    }

    /**
     * Opens an interactive terminal editor to view and modify deployment variables and secrets.
     * Useful for setting up AWS credentials, API keys, and other sensitive configuration.
     */
    public fun editVars() {
        write()
        println("Settings fulfilled: ${settings.keys}")
        println("Variables from server: $variables")
        secretsSource.runTerminalEditor(pseudoVariables + variables)
    }
}

/**
 * Executes a terraform command in this directory with the given environment variables.
 * Streams output to stdout/stderr and throws an exception if terraform exits with a non-zero code.
 *
 * @param environment Environment variables to set for the terraform process
 * @param args The terraform command and arguments (e.g., "init", "-upgrade")
 * @throws Exception if terraform exits with a non-zero exit code
 */
private fun File.runTerraform(defaultBinaryName: String, environment: Map<String, String>, vararg args: String) {
    val terraformBinary = System.getenv("${defaultBinaryName.uppercase()}_BINARY") ?: defaultBinaryName
    val result = ProcessBuilder(terraformBinary, *args)
        .directory(this)
        .also { it.environment().putAll(environment) }
        .inheritIO()
        .start()
        .waitFor()
    if (result != 0) {
        throw Exception("Terraform exited with result $result")
    }
}

/**
 * Interface for objects that can perform deployment operations.
 * Typically implemented by deployment configuration objects.
 */
public interface Deployment {
    /** Executes the deployment workflow. */
    public fun deploy()
}

/**
 * Configures a [SecretBasis] setting to be automatically generated by Terraform using a random password.
 * The generated value is a secure 88-character string with special characters.
 *
 * **Important**: This should be called within the context of a TerraformEmitterAws and within your
 * deployment configuration's settings() function.
 *
 * Example:
 * ```
 * override fun Server.settings() {
 *     secretBasis.generated()
 *     // ... other settings
 * }
 * ```
 */
context(emitter: TerraformEmitterAws)
public fun TerraformNeed<SecretBasis>.generated(): Unit {
    emitter.fulfillSetting(name, JsonPrimitive(expression("random_password.${name}.result")))
    emitter.emit("secretBasis") {
        "resource.random_password.${name}" {
            "length" - 88
            "special" - true
            "override_special" - "+/"
        }
    }
}
