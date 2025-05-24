package com.lightningkite.lightningdb

import com.google.devtools.ksp.symbol.*
import com.lightningkite.lightningserver.db.defaultText
import com.lightningkite.lightningserver.db.isMarkedNullable

data class Field(
    val name: String,
    val kotlinType: KSTypeReference,
    val annotations: List<ResolvedAnnotation>,
    val default: String?,
) {
    val nullable: Boolean = kotlinType.isMarkedNullable
}

private fun toField(owner: KSClassDeclaration, param: KSValueParameter, property: KSPropertyDeclaration): Field {
    val regexes = owner.primaryConstructor?.parameters?.map { other ->
        Regex("([^a-zA-Z0-9]|^)${other.name?.asString() ?: "xxx"}([^a-zA-Z0-9]|$)")
    } ?: listOf()
    return Field(
        name = property.simpleName.getShortName(),
        kotlinType = property.type,
        annotations = property.annotations.map { it.resolve() }.toList(),
        default = param.defaultText?.takeUnless {
            regexes.any { r ->
                r.find(it) != null
            } == true
        },
    )
}

fun KSClassDeclaration.fields(): List<Field> {
    val allProps = getAllProperties().associateBy { it.simpleName.getShortName() }
    return (primaryConstructor ?: throw IllegalArgumentException("No primary constructor found for ${this.qualifiedName?.asString()}"))
        .parameters
        .filter { it.isVal || it.isVar }
        .mapNotNull { toField(this, it, allProps[it.name?.getShortName()] ?: return@mapNotNull  null) }
}