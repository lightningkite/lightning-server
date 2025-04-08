package com.lightningkite.lightningserver.aws.terraform

data class TerraformProvider(
    val name: String,
    val source: String,
    val version: String,
) {
    companion object {
        val aws = TerraformProvider("aws", "hashicorp/aws", "~> 5.89.0")
        val random = TerraformProvider("random", "hashicorp/random", "~> 3.7.1")
        val archive = TerraformProvider("archive", "hashicorp/archive", "~> 2.7.0")
        val mongodbatlas = TerraformProvider("mongodbatlas", "mongodb/mongodbatlas", "~> 1.28.0")
        val local = TerraformProvider("local", "hashicorp/local", "~> 2.5.2")
        val nullProvider = TerraformProvider("null", "hashicorp/null", "~> 3.2.3")
    }
}