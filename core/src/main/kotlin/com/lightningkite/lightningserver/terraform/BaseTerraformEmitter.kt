package com.lightningkite.lightningserver.terraform

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.random.Random

public abstract class BaseTerraformEmitter<S : ServerBuilder> : TerraformEmitter {
    protected abstract val builder: S
    public abstract val secretsSource: SecretSource
    public abstract val terraformRoot: File
    public abstract fun S.settings()

    protected open val additionalSettings: Set<ServerSetting<*, *>> get() = setOf()
    protected val terraformProviderImports: MutableSet<TerraformProviderImport> = HashSet()
    override fun require(provider: TerraformProviderImport) {
        terraformProviderImports += provider
    }

    protected val terraformProviders: MutableSet<TerraformProvider> = HashSet()
    override fun require(provider: TerraformProvider) {
        terraformProviders += provider
    }

    protected val settings: MutableMap<String, JsonElement> = HashMap()
    override fun fulfillSetting(settingName: String, element: JsonElement) {
        settings[settingName] = element
    }

    protected val variables: MutableList<TerraformNeed<*>> = ArrayList()
    override fun variable(need: TerraformNeed<*>) {
        variables += need
    }

    protected val files: MutableMap<String, TerraformJsonObject> = mutableMapOf()
    override fun emit(
        context: String?,
        action: TerraformJsonObject.() -> Unit
    ) {
        files.getOrPut(context ?: "unclassified") { TerraformJsonObject() }.action()
    }

    protected open fun finalize(): Unit {
        builder.settings()
        val required = builder.build().settings.map { it.name }.toSet() + additionalSettings.map { it.name }
        val missing = required - settings.keys
        if (missing.isNotEmpty()) throw IllegalStateException("Missing settings for deployment ${projectPrefix}: $missing")
    }

    private var finalized = false
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
    }

    public companion object {
        public val AWS_ACCESS_KEY_ID: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_ACCESS_KEY_ID"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "Generated in the AWS Console."
        }
        public val AWS_SECRET_ACCESS_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_SECRET_ACCESS_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "Generated in the AWS Console."
        }
        public val AWS_PROFILE: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "AWS_PROFILE"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String =
                "The name of the AWS profile (from the current machine) that should be used for authenticating AWS."
        }
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
        public val MONGODB_ATLAS_PUBLIC_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "MONGODB_ATLAS_PUBLIC_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "You can obtain this by making a key in the MongoDB Atlas console."
        }
        public val MONGODB_ATLAS_PRIVATE_KEY: TerraformNeed<String> = object : TerraformNeed<String> {
            override val name: String = "MONGODB_ATLAS_PRIVATE_KEY"
            override val serializer: KSerializer<String> = String.serializer()
            override val default: String? = null
            override val instructions: String = "You can obtain this by making a key in the MongoDB Atlas console."
        }
    }

    public val psuedoVariables: List<TerraformNeed<*>> = listOf(
        AWS_ACCESS_KEY_ID,
        AWS_SECRET_ACCESS_KEY,
        AWS_PROFILE,
        AWS_SSE_CUSTOMER_KEY,
        MONGODB_ATLAS_PUBLIC_KEY,
        MONGODB_ATLAS_PRIVATE_KEY,
    )

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
                val usesEncryption = it.jsonObject?.get("terraform")
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
            fun SerialDescriptor.isActuallyPrimitive(): Boolean = kind is PrimitiveKind || isInline && getElementDescriptor(0).isActuallyPrimitive()
            if(serializer.descriptor.isActuallyPrimitive()) {
                env["TF_VAR_${need.name}"] = Json.encodeToJsonElement(serializer, secretsSource.get(need)).jsonPrimitive.content
            } else {
                env["TF_VAR_${need.name}"] = Json.encodeToString(serializer, secretsSource.get(need))
            }
        }

        return env
    }

    private val terraformEnv by lazy { prepareTerraform() }
    public fun terraform(vararg command: String) {
        terraformRoot.runTerraform(terraformEnv, *command)
    }

    public open fun deploy() {
        println("Initializing...")
        terraform("init", "-upgrade", "-input=false", "-no-color")
        println("Planning...")
        terraform("plan", "-input=false", "-no-color", "-out=plan.tfplan")
        println("Press enter to continue with plan...")
        readln()
        println("Applying...")
        terraform("apply", "-input=false", "-auto-approve", "-no-color", "plan.tfplan")
        println("Deployed!")
    }

    public fun editVars() {
        write()
        println("Settings fulfilled: ${settings.keys}")
        println("Variables from server: $variables")
        secretsSource.runTerminalEditor(psuedoVariables + variables)
    }

    public fun terraformShell() {
        write()
        while(true) {
            val next = readlnOrNull()?.also { if(it.isBlank()) continue } ?: break
            terraform(*next.split(' ').toTypedArray())
        }
    }
}

private fun File.runTerraform(environment: Map<String, String>, vararg args: String) {
//    val err = StringBuilder()
//    val out = StringBuilder()
    val result = ProcessBuilder("terraform", *args)
        .directory(this)
        .also { it.environment().putAll(environment) }
        .inheritIO()
        .start()
        .also {
//            it.errorStream.reader().use { reader ->
//                while(true) {
//                    val next = reader.read()
//                    if(next == -1) break
//                    print(next)
//                    out.append(next.toChar())
//                }
//            }
        }
        .waitFor()
    if (result != 0) {
        throw Exception("Terraform exited with result $result")
    }
}

public interface Deployment {
    public fun deploy()
}

context(emitter: TerraformEmitterAws) public fun TerraformNeed<SecretBasis>.generated(): Unit {
    emitter.fulfillSetting(name, JsonPrimitive(expression("random_password.${name}.result")))
    emitter.emit("secretBasis") {
        "resource.random_password.${name}" {
            "length" - 88
            "special" - true
            "override_special" - "+/"
        }
    }
}
