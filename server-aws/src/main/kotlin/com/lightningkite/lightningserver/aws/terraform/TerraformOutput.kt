package com.lightningkite.lightningserver.aws.terraform

internal data class TerraformOutput(val name: String, val value: String, val sensitive: Boolean = false) {
    companion object {

    }
}