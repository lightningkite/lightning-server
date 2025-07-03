package com.lightningkite.lightningdb

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import kotlinx.coroutines.flow.merge
import java.io.BufferedWriter
import java.io.File
import java.util.*
import kotlin.collections.HashSet

lateinit var comparable: KSClassDeclaration

class TableGenerator(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger,
) : CommonSymbolProcessor2(codeGenerator, "lightningdb", 57) {
    fun KSClassDeclaration.isSerializable(): Boolean = this.annotation("Serializable", "kotlinx.serialization") != null
    fun KSClassDeclaration.isPlainSerializable(): Boolean =
        this.annotation("Serializable", "kotlinx.serialization")?.let {
            log.appendLine("ARGS: " + it.arguments)
            it.arguments.all { it.value.toString() == "KSerializer<*>" }.also {
                log.appendLine("${this.qualifiedName?.asString()} isPlain? $it")
            } && this.classKind == ClassKind.CLASS && this.modifiers.any { it == Modifier.DATA }
        } ?: false

    fun KSClassDeclaration.needsDcp(): Boolean =
        annotation("DatabaseModel") != null || annotation("GenerateDataClassPaths") != null

    val internalOrMore = setOf(Visibility.INTERNAL, Visibility.PUBLIC)

    override fun interestedIn(resolver: Resolver): Set<KSFile> {
        return resolver.getAllFiles()
            .filter {
                it.declarations
                    .filterIsInstance<KSClassDeclaration>()
                    .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
                    .any {
                        it.isSerializable() && it.getVisibility() in internalOrMore ||
                                it.needsDcp() ||
                                it.annotation("SerialInfo", "kotlinx.serialization") != null
                    }
            }
            .toSet()
    }

    @OptIn(KspExperimental::class)
    override fun process2(resolver: Resolver, files: Set<KSFile>) {
        if(files.isEmpty()) return
        val allDatabaseModels = files
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.needsDcp() }

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
                    Exception(
                        "Failed to generate fields for ${it.declaration.qualifiedName?.asString()}",
                        e
                    ).printStackTrace()
//                    throw Exception("Failed to generate fields for ${it.declaration.qualifiedName?.asString()}", e)
                }
            }

        seen.clear()

        val allToProcess = files
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { sequenceOf(it) + it.declarations.filterIsInstance<KSClassDeclaration>() }
            .filter { it.isSerializable() || it.annotation("SerialInfo", "kotlinx.serialization") != null }

        fun <T> List<T>.commonPrefixWith(other: List<T>): List<T> {
            val i = indices.indexOfFirst { this.getOrNull(it) != other.getOrNull(it) }
            if(i == -1) return this
            return subList(0, i)
        }
        val mergedName = allToProcess.asSequence().map { it.packageName.asString().split('.') }.reduceOrNull { acc, t ->
            acc.commonPrefixWith(t)
        }?.joinToString(".")?.trim('.') ?: ""
        val module = (resolver.getModuleName()).asString()
            .substringAfter(':')
            .pascalCase()
            .let {
                // undo stupid Android bullshit
                val isTest = it.endsWith("Test")
                val isMain = it.endsWith("Main")
                it
                    .removeSuffix("Common")
                    .removeSuffix("Main")
                    .removeSuffix("Test")
                    .removeSuffix("Common")
                    .removeSuffix("Unit")
                    .removeSuffix("Debug")
                    .removeSuffix("Release")
                    .let { if(isTest) it + "Test" else it }
            }
        createNewFile(
            dependencies = Dependencies.ALL_FILES,
            packageName = mergedName,
            fileName = "prepareModels$module"
        ).use { out ->
            with(TabAppendable(out)) {
                if (mergedName.isNotEmpty()) appendLine("package $mergedName")
                appendLine()
                appendLine("import com.lightningkite.serialization.*")
                appendLine("import kotlinx.serialization.ExperimentalSerializationApi")
                appendLine("import kotlinx.serialization.builtins.NothingSerializer")
                appendLine()
                appendLine("fun prepareModels$module() { ${module}ModelsObject }")
                appendLine("object ${module}ModelsObject {")
                tab {
                    appendLine("init {")
                    tab {
                        appendLine("SerializationRegistry.master.register$module()")
                        allToProcess
                            .forEach {
                                if (it.classKind == ClassKind.ANNOTATION_CLASS)
                                    it.handleSerializableAnno(this)
                                else if (it.needsDcp())
                                    appendLine("${it.packageName.asString()}.prepare${it.simpleName.asString()}Fields()")

                            }
                    }
                    appendLine("}")
                }
                appendLine("}")
                appendLine()
                appendLine("@OptIn(ExperimentalSerializationApi::class)")
                appendLine("fun SerializationRegistry.register$module() {")
                tab {
//                    appendLine("prepareModels$module()")
                    allToProcess
                        .forEach {
                            if (it.isSerializable() && it.getVisibility() in internalOrMore) {
                                val classReference: String = it.qualifiedName!!.asString()
                                if (it.typeParameters.isEmpty())
                                    appendLine("register($classReference.serializer())")
                                else
                                    appendLine("register($classReference.serializer(${it.typeParameters.indices.joinToString() { "NothingSerializer()" }}).descriptor.serialName) { $classReference.serializer(${it.typeParameters.indices.joinToString() { "it[$it]" }}) }")
                            }
                        }
                }
                appendLine("}")
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