package com.lightningkite.lightningserver.terraform

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformEmitterAws
import com.lightningkite.services.terraform.TerraformJsonObject
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProvider
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

public abstract class BaseTerraformEmitter: TerraformEmitter {
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

    public fun <S: ServerBuilder> settings(builder: S, configure: S.()->Unit) {
        val required = builder.build().settings.map { it.name }.toSet()
        with(builder) { configure() }
        val missing = required - settings.keys
        if(missing.isNotEmpty()) throw IllegalStateException("Missing settings: $missing")
    }

    protected val files: MutableMap<String, TerraformJsonObject> = mutableMapOf()
    override fun emit(
        context: String?,
        action: TerraformJsonObject.() -> Unit
    ) {
        files.getOrPut(context ?: "unclassified") { TerraformJsonObject() }.action()
    }

    protected abstract fun finalize(): Unit

    public fun write(directory: File) {
        finalize()
        val prettyJson = Json { prettyPrint = true }
        for ((name, content) in files.entries) {
            directory.resolve("$name.tf.json").writeText(prettyJson.encodeToString(content.toJsonObject()))
        }
    }
}


context(emitter: TerraformEmitterAws) public fun TerraformNeed<SecretBasis>.generated(): Unit {
    emitter.fulfillSetting(name, JsonPrimitive(expression("random_password.${name}.result")))
    emitter.emit("secretBasis") {
        "resource.random_password.${name}" {
            "length"           - 88
            "special"          - true
            "override_special" - "+/"
        }
    }
}