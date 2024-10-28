package com.lightningkite.lightningserver.aws.terraform

internal data class TerraformProvider(
    val name: String,
    val source: String,
    val version: String,
) {
    companion object {
        val aws = TerraformProvider("aws", "hashicorp/aws", "~> 4.30")
        val random = TerraformProvider("random", "hashicorp/random", "~> 3.1.0")
        val archive = TerraformProvider("archive", "hashicorp/archive", "~> 2.2.0")
        val mongodbatlas = TerraformProvider("mongodbatlas", "mongodb/mongodbatlas", "~> 1.4")
        val local = TerraformProvider("local", "hashicorp/local", "~> 2.2")
        val nullProvider = TerraformProvider("null", "hashicorp/null", "~> 3.2")
    }
}