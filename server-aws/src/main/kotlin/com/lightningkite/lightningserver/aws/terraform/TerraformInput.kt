package com.lightningkite.lightningserver.aws.terraform

internal data class TerraformInput(
    val name: String,
    val type: String,
    val default: String?,
    val nullable: Boolean = false,
    val description: String? = null,
    val validations: List<Validation> = emptyList(),
) {
    companion object {
        fun stringList(name: String, default: List<String>?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(
                name,
                "list(string)",
                default?.joinToString(", ", "[", "]") { "\"$it\"" },
                nullable = nullable,
                description = description,
            )

        fun string(name: String, default: String?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "string", default?.let { "\"$it\"" }, nullable = nullable, description = description)

        fun boolean(name: String, default: Boolean?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "bool", default?.toString(), nullable = nullable, description = description)

        fun number(name: String, default: Number?, nullable: Boolean = false, description: String? = null) =
            TerraformInput(name, "number", default?.toString(), nullable = nullable, description = description)
    }
}