package com.lightningkite.lightningserver.aws.terraform

data class Validation(
    val condition: String,
    val errorMessage: String,
)