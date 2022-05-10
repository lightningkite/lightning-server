package com.lightningkite.ktordb

import com.google.devtools.ksp.symbol.*

data class Field(
    val name: String,
    val kotlinType: KSTypeReference,
    val annotations: List<ResolvedAnnotation>
) {
    val nullable: Boolean = kotlinType.isMarkedNullable
}

fun KSPropertyDeclaration.toField(): Field {
    return Field(
        name = this.simpleName.getShortName(),
        kotlinType = this.type,
        annotations = this.annotations.map { it.resolve() }.toList()
    )
}

fun KSClassDeclaration.fields(): List<Field> {
    val allProps = getAllProperties().associateBy { it.simpleName.getShortName() }
    return (primaryConstructor ?: throw IllegalArgumentException("No primary constructor found for ${this.qualifiedName?.asString()}"))
        .parameters
        .filter { it.isVal || it.isVar }
        .mapNotNull { allProps[it.name?.getShortName()] }
        .map { it.toField() }
}