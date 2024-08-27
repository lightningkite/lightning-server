package com.lightningkite.lightningdb

import com.google.devtools.ksp.symbol.*
import org.jetbrains.kotlin.util.capitalizeDecapitalize.decapitalizeAsciiOnly
import kotlin.math.min

data class ResolvedAnnotation(
    val type: KSClassDeclaration,
    val arguments: Map<String, Any?>
)

fun KSTypeReference.tryResolve(): KSType? = try {
    resolve()
} catch (e: Exception) {
    null
}

val KSDeclaration.importSafeName: String
    get() = when (packageName.asString()) {
        "kotlin", "kotlin.collection", "com.lightningkite.lightningdb", "org.jetbrains.exposed.sql" -> this.simpleName.asString()
        else -> this.qualifiedName!!.asString()
    }

fun KSDeclaration.safeLocalReference(): String = qualifiedName!!.asString().split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")

fun KSTypeReference.toKotlin(annotations: Sequence<KSAnnotation>? = null): String =
    this.resolve().toKotlin(annotations ?: sequenceOf())

fun KSType.toKotlin(annotations: Sequence<KSAnnotation> = this.annotations): String {
    (this.declaration as? KSTypeParameter)?.let { return it.name.asString() + if (isMarkedNullable) "?" else "" }

    val annotationString = annotations.joinToString(" ") {
        it.toString()
    }.let { if (it.isBlank()) "" else "$it " }

    return annotationString + (declaration.safeLocalReference() + if (arguments.isNotEmpty()) {
        arguments.joinToString(", ", "<", ">") { it.type?.toKotlin() ?: "*" }
    } else "") + if (isMarkedNullable) "?" else ""
}

fun KSType.toKotlinSerializer(contextualTypes: List<KSDeclaration>): String {
    val allAnnos = this.annotations.asSequence() + generateSequence((this as? KSTypeAlias)?.type?.tryResolve()) {
        (it as? KSTypeAlias)?.type?.tryResolve()
    }.flatMap { it.annotations }
    return run {
        if (allAnnos.any { it.resolve().type.qualifiedName?.asString() == "kotlinx.serialization.Contextual" }) return@run "ContextualSerializer(${
            this.makeNotNullable().toKotlin(sequenceOf())
        }::class)"
        if (this.declaration in contextualTypes) return@run "ContextualSerializer(${
            this.makeNotNullable().toKotlin(sequenceOf())
        }::class)"
        if ((this.declaration as? KSTypeAlias)?.type?.tryResolve()?.declaration in contextualTypes) return@run "ContextualSerializer(${
            this.makeNotNullable().toKotlin(sequenceOf())
        }::class)"
        val typeArgsAndEnding =
            arguments.joinToString(", ", "(", ")") { it.type?.resolve()?.toKotlinSerializer(contextualTypes) ?: "*" }
        when (this.declaration.qualifiedName?.asString()) {
            "kotlin.collections.List" -> return@run "ListSerializer$typeArgsAndEnding"
            "kotlin.collections.Set" -> return@run "SetSerializer$typeArgsAndEnding"
            "kotlin.collections.Map" -> return@run "MapSerializer$typeArgsAndEnding"
        }
        (this.declaration as? KSTypeParameter)?.let { return@run it.name.asString().decapitalizeAsciiOnly() }

        return@run declaration.safeLocalReference() + ".serializer$typeArgsAndEnding"
    } + if (isMarkedNullable) ".nullable2" else ""
}

fun KSType.toKotlinLeast(annotations: Sequence<KSAnnotation> = this.annotations, alreadyProcessed: Set<KSName> = setOf()): String {
    (this.declaration as? KSTypeParameter)?.let { tp ->
        if(alreadyProcessed.contains(tp.name)) return "Any?"
        return tp.bounds.firstOrNull()?.resolve()?.toKotlinLeast(alreadyProcessed = alreadyProcessed + tp.name) ?: "Any?"
    }

    val annotationString = annotations.joinToString(" ") {
        it.toString()
    }.let { if (it.isBlank()) "" else "$it " }

    return annotationString + (declaration.safeLocalReference() + if (arguments.isNotEmpty() && this.declaration !is KSTypeAlias) {
        arguments.joinToString(", ", "<", ">") { it.type?.resolve()?.toKotlinLeast(alreadyProcessed = alreadyProcessed) ?: "*" }
    } else "") + if (isMarkedNullable) "?" else ""
}

fun List<ResolvedAnnotation>.byName(
    name: String,
    packageName: String = "com.lightningkite.lightningdb"
): ResolvedAnnotation? = this.find {
    it.type.qualifiedName?.asString() == "$packageName.$name"
}

fun KSAnnotation.resolve(): ResolvedAnnotation {
    val type = this.annotationType.resolve().declaration as KSClassDeclaration
    val params = type.primaryConstructor?.parameters ?: listOf()
    return ResolvedAnnotation(
        type = type,
        arguments = this.arguments.withIndex().associate {
            val paramName =
                it.value.name?.getShortName() ?: params[min(params.lastIndex, it.index)].name!!.getShortName()
            paramName to it.value.value
        }
    )
}

fun KSAnnotated.annotation(name: String, packageName: String = "com.lightningkite.lightningdb"): KSAnnotation? {
    return this.annotations.find {
        it.shortName.getShortName() == name &&
                it.annotationType.resolve().declaration.qualifiedName?.asString() == "$packageName.$name"
    }
}

internal fun KSDeclaration.findClassDeclaration(): KSDeclaration? {
    return when (this) {
        is KSClassDeclaration -> this
        is KSTypeAlias -> this.type.resolve().declaration
        else -> null
    }
}


internal fun KSType.allClassDeclarations(): Sequence<KSClassDeclaration> {
    return sequenceOf(this.declaration).plus(
        this.arguments.asSequence().flatMap { it.type?.resolve()?.allClassDeclarations() ?: sequenceOf() }
    ).mapNotNull { it as? KSClassDeclaration }
}
