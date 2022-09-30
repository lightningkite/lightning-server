package com.lightningkite.lightningdb

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*

lateinit var comparable: KSClassDeclaration
var khrysalisUsed = false

class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : SymbolProcessor {
    val deferredSymbols = ArrayList<KSClassDeclaration>()
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val allDatabaseModels = resolver.getNewFiles()
            .flatMap { it.declarations }
            .mapNotNull { it as? KSClassDeclaration }
            .filter { it.annotation("DatabaseModel") != null }
        val changedDatabaseModels = resolver.getNewFiles()
            .flatMap { it.declarations }
            .mapNotNull { it as? KSClassDeclaration }
            .filter { it.annotation("DatabaseModel") != null }

        val seen = HashSet<KSClassDeclaration>()
        resolver.getClassDeclarationByName("kotlin.Comparable")?.let { comparable = it }
        changedDatabaseModels
            .flatMap { MongoFields(it).allSubs(seen) }
            .distinct()
            .forEach {
                try {
                    codeGenerator.createNewFile(
                        dependencies = it.declaration.containingFile?.let { Dependencies(false, it) }
                            ?: Dependencies.ALL_FILES,
                        packageName = it.packageName,
                        fileName = it.simpleName + "Fields"
                    ).bufferedWriter().use { out ->
                        it.write(TabAppendable(out))
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e)
                }
            }

        seen.clear()

        allDatabaseModels
            .flatMap { MongoFields(it).allSubs(seen) }
            .distinct()
            .groupBy { it.packageName }
            .forEach { ksName, ksClassDeclarations ->
                codeGenerator.createNewFile(
                    dependencies = Dependencies.ALL_FILES,
                    packageName = ksName,
                    fileName = "init"
                ).bufferedWriter().use { out ->
                    with(TabAppendable(out)) {

                        if(khrysalisUsed) {
                            appendLine("@file:SharedCode")
                        }
                        appendLine("package ${ksName}")
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
        return deferredSymbols
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