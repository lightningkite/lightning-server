package com.lightningkite.ktordb

import com.google.devtools.ksp.symbol.*
import kotlin.math.min

val KSTypeReference.usesSub: Boolean get() = (this.resolve().declaration as? KSClassDeclaration)?.usesSub ?: false
val KSClassDeclaration.usesSub: Boolean
    get() {
        val packageName = this.packageName.asString()
        return when {
            packageName.startsWith("org.litote.kmongo") ||
                    packageName.startsWith("java.time") ||
                    packageName.startsWith("java.util") ||
                    packageName.startsWith("kotlin") -> false
            packageName == "com.lightningkite.ktordb" && (
                    this.simpleName.asString() == "Condition" ||
                    this.simpleName.asString() == "Modification"
            ) -> false
//            packageName.startsWith("kotlin.Byte") ||
//            packageName.startsWith("kotlin.Short") ||
//            packageName.startsWith("kotlin.Int") ||
//            packageName.startsWith("kotlin.Long") ||
//            packageName.startsWith("kotlin.Float") ||
//            packageName.startsWith("kotlin.Double") ||
//            packageName.startsWith("kotlin.String") ||
//            packageName.startsWith("kotlin.collections") ||
//            packageName.startsWith("kotlin.Boolean") -> false
            else -> this.annotations.any { it.shortName.asString() == "Serializable" } && this.annotations.none { it.shortName.asString() == "DoNotGenerateFields" }
        }
    }

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
        "kotlin", "kotlin.collection", "com.lightningkite.ktordb", "org.jetbrains.exposed.sql" -> this.simpleName.asString()
        else -> this.qualifiedName!!.asString()
    }

fun KSTypeReference.toKotlin(annotations: Sequence<KSAnnotation>? = null): String =
    this.resolve().toKotlin(annotations ?: this.resolve().annotations)

fun KSType.toKotlin(annotations: Sequence<KSAnnotation> = this.annotations): String {
    val annotationString = annotations.joinToString(" ") {
        it.toString()
    }.let { if (it.isBlank()) "" else "$it " }

    return if (declaration.qualifiedName!!.asString() == "com.lightningkite.ktordb.UUIDFor") {
        "${annotationString}UUIDFor<*>"
    } else {
        annotationString + (declaration.simpleName.asString() + if (arguments.isNotEmpty() && this.declaration !is KSTypeAlias) {
            arguments.joinToString(", ", "<", ">") { it.type?.toKotlin() ?: "*" }
        } else "")
    } + if (isMarkedNullable) "?" else ""
}

fun List<ResolvedAnnotation>.byName(
    name: String,
    packageName: String = "com.lightningkite.ktordb"
): ResolvedAnnotation? = this.find {
    it.type.qualifiedName?.asString() == "$packageName.$name"
}

fun KSAnnotation.resolve(): ResolvedAnnotation {
    val type = this.annotationType.resolve().declaration as KSClassDeclaration
    val params = type.primaryConstructor!!.parameters
    return ResolvedAnnotation(
        type = type,
        arguments = this.arguments.withIndex().associate {
            val paramName =
                it.value.name?.getShortName() ?: params[min(params.lastIndex, it.index)].name!!.getShortName()
            paramName to it.value.value
        }
    )
}

fun KSAnnotated.annotation(name: String, packageName: String = "com.lightningkite.ktordb"): KSAnnotation? {
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
