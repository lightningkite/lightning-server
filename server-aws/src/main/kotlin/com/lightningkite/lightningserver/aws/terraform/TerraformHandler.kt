package com.lightningkite.lightningserver.aws.terraform

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.util.HashMap

internal data class TerraformHandler(
    val name: String,
    val priority: Int = 0,
    val makeSection: TerraformProjectInfo.(settingKey: String) -> TerraformSection,
) {
    companion object {
        val handlers =
            HashMap<KSerializer<*>, HashMap<String, TerraformHandler>>()

        inline fun <reified T : Any> handler(
            name: String = "Standard",
            priority: Int = 0,
            providers: List<TerraformProvider> = listOf(
                TerraformProvider.aws,
                TerraformProvider.random,
                TerraformProvider.archive
            ),
            crossinline policies: TerraformProjectInfo.(settingKey: String)->List<String> = { listOf() },
            noinline inputs: TerraformProjectInfo.(settingKey: String) -> List<TerraformInput> = { listOf() },
            noinline emit: TerraformRequirementBuildInfo.() -> Unit = { },
            noinline settingOutput: TerraformProjectInfo.(settingKey: String) -> String,
        ) {
            handlers.getOrPut(serializer<T>()) { HashMap() }.put(name, TerraformHandler(name, priority) { it ->
                TerraformSection(
                    name = it,
                    providers = providers,
                    policies = policies(this, it),
                    inputs = inputs(this, it),
                    emit = { emit(TerraformRequirementBuildInfo(this@TerraformHandler, it, this)) },
                    toLightningServer = mapOf(it to settingOutput(this, it)),
                    outputs = listOf()
                )
            })
        }
    }
}