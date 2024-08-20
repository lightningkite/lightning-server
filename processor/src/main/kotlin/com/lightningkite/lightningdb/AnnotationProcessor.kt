package com.lightningkite.lightningdb

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.BufferedWriter
import java.io.File
import java.util.UUID

lateinit var comparable: KSClassDeclaration

class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor2(codeGenerator, "lightningdb", 16) {
    override fun interestedIn(resolver: Resolver): Set<KSFile> {
        return resolver.getAllFiles()
            .filter {
                it.declarations
                    .filterIsInstance<KSClassDeclaration>()
                    .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
                    .any {
                        it.annotation("DatabaseModel") != null ||
                                it.annotation("GenerateDataClassPaths") != null ||
                                it.annotation("SerialInfo", "kotlinx.serialization") != null
                    }
            }
            .toSet()
    }

    override fun process2(resolver: Resolver, files: Set<KSFile>) {
        val allDatabaseModels = files
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.annotation("DatabaseModel") != null || it.annotation("GenerateDataClassPaths") != null }
        val allToProcess = files
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.annotation("DatabaseModel") != null || it.annotation("GenerateDataClassPaths") != null || it.annotation("SerialInfo", "kotlinx.serialization") != null }

        val seen = HashSet<KSClassDeclaration>()
        resolver.getClassDeclarationByName("kotlin.Comparable")?.let { comparable = it }
        allDatabaseModels
            .map { MongoFields(it) }
            .distinct()
            .forEach {
                try {
                    createNewFile(
                        dependencies = it.declaration.containingFile?.let { Dependencies(false, it) }
                            ?: Dependencies.ALL_FILES,
                        packageName = it.packageName,
                        fileName = it.simpleName + "Fields"
                    ).use { out ->
                        it.write(TabAppendable(out))
                    }
                } catch (e: Exception) {
                    Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e).printStackTrace()
//                    throw Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e)
                }
            }

        seen.clear()

        allToProcess
            .groupBy { it.packageName }
            .forEach { ksName, ksClassDeclarations ->
                createNewFile(
                    dependencies = Dependencies.ALL_FILES,
                    packageName = ksName.asString(),
                    fileName = "init"
                ).use { out ->
                    with(TabAppendable(out)) {
                        if(ksName.asString().isNotEmpty()) appendLine("package ${ksName.asString()}")
                        appendLine("fun prepareModels() {")
                        tab {
                            ksClassDeclarations
                                .forEach {
                                    if(it.annotation("DatabaseModel") != null || it.annotation("GenerateDataClassPaths") != null) {
                                        appendLine("    prepare${it.simpleName.asString()}Fields()")
                                    } else {
                                        it.handleSerializableAnno(this)
                                    }
                                }
                        }
                        appendLine("}")
                    }
                }
            }

        logger.info("Complete.")
    }
}

class MyProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TableGenerator(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger,
        )
    }
}