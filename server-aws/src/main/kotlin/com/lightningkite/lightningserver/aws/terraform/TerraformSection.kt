package com.lightningkite.lightningserver.aws.terraform

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings

internal data class TerraformSection(
    val name: String,
    val providers: List<TerraformProvider> = listOf(
        TerraformProvider.aws,
        TerraformProvider.local,
        TerraformProvider.random,
        TerraformProvider.nullProvider,
        TerraformProvider.archive
    ),
    val policies: List<String> = listOf(),
    val inputs: List<TerraformInput> = listOf(),
    val emit: Appendable.() -> Unit = {},
    val toLightningServer: Map<String, String>? = null,
    val outputs: List<TerraformOutput> = listOf(),
) {
    companion object {

        fun <T> default(setting: Settings.Requirement<T, *>) = TerraformSection(
            name = setting.name,
            inputs = listOf(
                TerraformInput(
                    name = setting.name,
                    type = "any",
                    default = setting.default.let {
                        Serialization.Internal.json.encodeToString(
                            setting.serializer,
                            it
                        )
                    },
                    nullable = setting.serializer.descriptor.isNullable,
                    description = setting.description
                ),
            ),
            toLightningServer = mapOf(setting.name to "var.${setting.name}")
        )
    }
}