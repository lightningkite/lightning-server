package com.lightningkite.lightningserver.aws.terraform


data class TerraformRequirementBuildInfo(
    val project: TerraformProjectInfo,
    val name: String,
    val appendable: Appendable,
) : Appendable by appendable {
    val key: String get() = name
}

val TerraformRequirementBuildInfo.namePrefix: String get() = project.namePrefix
val TerraformRequirementBuildInfo.namePrefixLower: String get() = project.namePrefixLower
val TerraformRequirementBuildInfo.namePrefixUnderscores: String get() = project.namePrefixUnderscores
val TerraformRequirementBuildInfo.namePrefixSafe: String get() = project.namePrefixSafe
val TerraformRequirementBuildInfo.namePrefixPath: String get() = project.namePrefixPath
val TerraformRequirementBuildInfo.namePrefixPathSegment: String get() = project.namePrefixPathSegment