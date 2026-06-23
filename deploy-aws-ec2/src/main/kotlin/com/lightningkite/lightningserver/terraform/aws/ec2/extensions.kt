package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.services.terraform.TerraformEmitter

/**
 * Converts the project prefix to a path-safe format for IAM policy paths.
 * Replaces hyphens with forward slashes and removes underscores.
 */
public val TerraformEmitter.projectPrefixPath: String
    get() = projectPrefix.lowercase().replace("-", "/").replace("_", "")

