package com.lightningkite.lightningserver.aws.terraform


internal data class TerraformRequirementBuildInfo(
    val project: TerraformProjectInfo,
    val name: String,
    val appendable: Appendable,
) : Appendable by appendable {
    val key: String get() = name
}

internal val TerraformRequirementBuildInfo.namePrefix: String get() = project.namePrefix
internal val TerraformRequirementBuildInfo.namePrefixLower: String get() = project.namePrefixLower
internal val TerraformRequirementBuildInfo.namePrefixUnderscores: String get() = project.namePrefixUnderscores
internal val TerraformRequirementBuildInfo.namePrefixSafe: String get() = project.namePrefixSafe
internal val TerraformRequirementBuildInfo.namePrefixPath: String get() = project.namePrefixPath
internal val TerraformRequirementBuildInfo.namePrefixPathSegment: String get() = project.namePrefixPathSegment