package com.lightningkite.lightningserver.aws.terraform

internal data class Validation(
    val condition: String,
    val errorMessage: String,
)