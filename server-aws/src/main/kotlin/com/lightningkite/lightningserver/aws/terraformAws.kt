package com.lightningkite.lightningserver.aws

import java.io.File

@Deprecated("Use the direct one instead", ReplaceWith("com.lightningkite.lightningserver.aws.terraform.createTerraform"))
fun terraformAws(handlerFqn: String, projectName: String = "project", root: File)
    = com.lightningkite.lightningserver.aws.terraform.createTerraform(handlerFqn, projectName, root)