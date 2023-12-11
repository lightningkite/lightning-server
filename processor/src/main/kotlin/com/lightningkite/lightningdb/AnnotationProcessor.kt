package com.lightningkite.lightningdb

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.BufferedWriter
import java.io.File
import java.util.UUID

lateinit var comparable: KSClassDeclaration
var khrysalisUsed = false

class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor(codeGenerator) {
    override fun process2(resolver: Resolver) {
        val allDatabaseModels = resolver.getNewFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.annotation("DatabaseModel") != null || it.annotation("GenerateDataClassPaths") != null }
        val changedDatabaseModels = resolver.getNewFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.annotation("DatabaseModel") != null || it.annotation("GenerateDataClassPaths") != null }

        val seen = HashSet<KSClassDeclaration>()
        resolver.getClassDeclarationByName("kotlin.Comparable")?.let { comparable = it }
        changedDatabaseModels
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
                    createNewFile(
                        dependencies = it.declaration.containingFile?.let { Dependencies(false, it) }
                            ?: Dependencies.ALL_FILES,
                        packageName = it.packageName,
                        fileName = it.simpleName + "Fields",
                        extensionName = "ts.yaml"
                    ).use { out ->
                        it.writeTs(TabAppendable(out))
                    }
                    createNewFile(
                        dependencies = it.declaration.containingFile?.let { Dependencies(false, it) }
                            ?: Dependencies.ALL_FILES,
                        packageName = it.packageName,
                        fileName = it.simpleName + "Fields",
                        extensionName = "swift.yaml"
                    ).use { out ->
                        it.writeSwift(TabAppendable(out))
                    }
                } catch (e: Exception) {
                    Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e).printStackTrace()
//                    throw Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e)
                }
            }

        seen.clear()

        allDatabaseModels
            .map { MongoFields(it) }
            .distinct()
            .groupBy { it.packageName }
            .forEach { ksName, ksClassDeclarations ->
                createNewFile(
                    dependencies = Dependencies.ALL_FILES,
                    packageName = ksName,
                    fileName = "init"
                ).use { out ->
                    with(TabAppendable(out)) {

                        if(khrysalisUsed) {
                            appendLine("@file:SharedCode")
                        }
                        if(ksName.isNotEmpty()) appendLine("package ${ksName}")
                        if(khrysalisUsed) {
                            appendLine("import com.lightningkite.khrysalis.*")
                        }
                        appendLine("fun prepareModels() {")
                        tab {
                            ksClassDeclarations
                                .forEach {
                                    appendLine("    prepare${it.simpleName}Fields()")
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