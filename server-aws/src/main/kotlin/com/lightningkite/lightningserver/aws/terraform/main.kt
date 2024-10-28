package com.lightningkite.lightningserver.aws.terraform

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*


fun createTerraform(handlerFqn: String, projectName: String = "project", root: File) {
    root.mkdirs()
    root.listFiles()!!.filter { it.isDirectory }.plus(
        root.resolve("example")
    ).distinct().forEach { terraformEnvironmentAws(handlerFqn, it, projectName) }
}

private fun terraformEnvironmentAws(handlerFqn: String, folder: File, projectName: String = "project") {
    SettingsHandlers

    val projectInfoFile = folder.resolve("project.json")
    folder.mkdirs()
    val defaultHandlers = Settings.requirements.entries.associate {
        it.key to (TerraformHandler.handlers[it.value.serializer]?.maxBy { it.value.priority }?.key ?: "Direct")
    }
    val info = projectInfoFile
        .takeIf { it.exists() }
        ?.readText()
        ?.let { Serialization.Internal.json.decodeFromString(TerraformProjectInfo.serializer(), it) }
        ?.let { it.copy(handlers = defaultHandlers + it.handlers) }
        ?: TerraformProjectInfo(
            projectName = projectName,
            bucket = "your-deployment-bucket",
            vpc = false,
            domain = true,
            profile = "default",
            handlers = defaultHandlers,
        )
    @Suppress("JSON_FORMAT_REDUNDANT")
    projectInfoFile.writeText(
        Json(Serialization.Internal.json) { prettyPrint = true }
            .encodeToString(TerraformProjectInfo.serializer(), info)
    )

    val settingsSections = Settings.requirements.values.map {
        val handler = TerraformHandler.handlers[it.serializer]?.let { handlers ->
            val handlerName = info.handlers.get(it.name)
            if (handlerName == "Direct") return@let null
            handlers[handlerName]!!
        }
        handler?.makeSection?.invoke(info, it.name) ?: TerraformSection.default(it)
    }
    val allSections = when(info.core){
        TerraformCoreType.Lambda -> {
            val sections = listOf(
                settingsSections,
                listOf(defaultAwsHandler(info)),
                listOfNotNull(
                    httpAwsHandler(info),
                    wsAwsHandler(info),
                    awsLambdaCloudwatch(info),
                ),
                scheduleAwsHandlers(info)
            ).flatten()
            sections + awsLambdaHandler(info, handlerFqn, sections)
        }
        TerraformCoreType.SingleEC2 -> {
            val others = settingsSections + defaultAwsHandler(info)
            others + awsEc2Handler(info, others)
        }
        TerraformCoreType.ELB -> TODO()
    }

    val sectionToFile = allSections.associateWith { section ->
        folder.resolve(section.name.filter { it.isLetterOrDigit() } + ".tf")
    }
    val warning = "# Generated via Lightning Server.  This file will be overwritten or deleted when regenerating."
    folder.listFiles()!!.filter {
        it.extension == "tf" && it.readText().contains(warning)
    }.forEach { it.delete() }
    for ((section, file) in sectionToFile) {
//        if(!file.readText().contains(warning)) continue
        file.printWriter().use { it ->
            it.appendLine(warning)
            it.appendLine("##########")
            it.appendLine("# Inputs")
            it.appendLine("##########")
            it.appendLine()
            for (input in section.inputs) {
                it.appendLine("variable \"${input.name}\" {")
                it.appendLine("    type = ${input.type}")
                input.default?.let { d ->
                    it.appendLine("    default = $d")
                }
                it.appendLine("    nullable = ${input.nullable}")
                input.description?.let { d ->
                    it.appendLine("    description = \"$d\"")
                }
                input.validations.forEach { validation ->
                    it.appendLine("    validation {")
                    it.appendLine("        condition = ${validation.condition}")
                    it.appendLine("        error_message = ${validation.errorMessage}")
                    it.appendLine("    }")
                }
                it.appendLine("}")
            }
            it.appendLine()
            it.appendLine("##########")
            it.appendLine("# Outputs")
            it.appendLine("##########")
            it.appendLine()
            for (output in section.outputs) {
                it.appendLine("output \"${output.name}\" {")
                it.appendLine("    value = ${output.value}")
                if(output.sensitive) {
                    it.appendLine("    sensitive = true")
                }
                it.appendLine("}")
            }
            it.appendLine()
            it.appendLine("##########")
            it.appendLine("# Resources")
            it.appendLine("##########")
            it.appendLine()
            section.emit(it)
            it.appendLine()
        }
    }

    val usingMongo = allSections.any { it.providers.any { it.name == "mongodbatlas" } }
    if (usingMongo) {
        fun get(name: String): String {
            println("$name for profile ${info.profile}:")
            return readln()
        }

        val mongoCredsFile = File(System.getProperty("user.home")).resolve(".mongo/profiles/${info.profile}.env")
        val mongoCredsFile2 = File(System.getProperty("user.home")).resolve(".mongo/profiles/${info.profile}.ps1")
        mongoCredsFile.parentFile.mkdirs()
        if (!mongoCredsFile.exists()) {
            val mongoPublic = if (usingMongo) get("MongoDB Public Key") else null
            val mongoPrivate = if (usingMongo) get("MongoDB Private Key") else null
            mongoCredsFile.writeText(
                """
                    MONGODB_ATLAS_PUBLIC_KEY="$mongoPublic"
                    MONGODB_ATLAS_PRIVATE_KEY="$mongoPrivate"
                """.trimIndent() + "\n"
            )
            mongoCredsFile.setExecutable(true)
            mongoCredsFile2.writeText(
                """
                    ${'$'}env:MONGODB_ATLAS_PUBLIC_KEY = "$mongoPublic"
                    ${'$'}env:MONGODB_ATLAS_PRIVATE_KEY = "$mongoPrivate"
                """.trimIndent() + "\n"
            )
            mongoCredsFile2.setExecutable(true)
        }
    }

    folder.resolve("tf").printWriter().use {
        it.appendLine("#!/bin/bash")
        it.appendLine("export AWS_PROFILE=${info.profile}")
        if (usingMongo) {
            it.appendLine(
                """
                  export ${'$'}(cat ~/.mongo/profiles/${info.profile}.env | xargs)
            """.trimIndent()
            )
        }
        it.appendLine("terraform \"$@\"")
    }
    folder.resolve("tf").setExecutable(true)

    folder.resolve("tf.ps1").printWriter().use {
        it.appendLine("\$env:AWS_PROFILE = \"${info.profile}\"")
        if (usingMongo) {
            it.appendLine(
                """
                  . ~/.mongo/profiles/${info.profile}.ps1
            """.trimIndent()
            )
        }
        it.appendLine("terraform \$args")
    }
    folder.resolve("tf.ps1").setExecutable(true)

    folder.resolve("main.tf").printWriter().use {
        it.appendLine("""terraform {""")
        it.appendLine("  required_providers {")
        for (provider in allSections.flatMap { it.providers }.distinct()) {
            it.appendLine("    ${provider.name} = {")
            it.appendLine("      source = \"${provider.source}\"")
            it.appendLine("      version = \"${provider.version}\"")
            it.appendLine("    }")
        }
        it.appendLine("  }")
        it.appendLine("""  backend "s3" {""")
        it.appendLine("""    bucket = "${info.bucket}"""")
        info.bucketPathOverride?.let { override ->
            it.appendLine("""    key    = "${override}"""")
        } ?: run {
            it.appendLine("""    key    = "${info.projectNameSafe}/${folder.name}"""")
        }
        it.appendLine("""    region = "us-west-2"""")
        it.appendLine("""  }""")
        it.appendLine("""}""")
        it.appendLine("""provider "aws" {""")
        it.appendLine("""  region = "us-west-2"""")
        it.appendLine("""}""")
        it.appendLine("""provider "aws" {""")
        it.appendLine("""  alias = "acm"""")
        it.appendLine("""  region = "us-east-1"""")
        it.appendLine("""}""")
        if (usingMongo) {
            it.appendLine(
                """   
                provider "mongodbatlas" {
                }
            """.trimIndent()
            )
        }
    }

    folder.resolve("terraform.tfvars").takeUnless { it.exists() }?.writeText(
        allSections.flatMap { it.inputs }.distinct().joinToString("\n") { it.name + " = " + it.default } + "\n"
    )
}
