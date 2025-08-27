package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.terraform.TerraformEmitter

public val TerraformEmitter.projectPrefixPath: String
    get() = projectPrefix.lowercase().replace("-", "/").replace("_", "")
