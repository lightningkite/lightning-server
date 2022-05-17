package com.lightningkite.ktorbatteries.typed

import com.lightningkite.ktorbatteries.routes.docName
import io.ktor.server.routing.*
import kotlinx.html.INPUT
import kotlinx.html.OUTPUT
import kotlin.reflect.KType

interface Documentable {
    val route: Route
    val summary: String
    val description: String
    val userType: KType?
}
val Documentable.docGroup: String? get() = generateSequence(route) { it.parent }.mapNotNull { it.docName }.firstOrNull()
val Documentable.functionName: String get() = summary.split(' ').joinToString("") { it.replaceFirstChar { it.uppercase() } }.replaceFirstChar { it.lowercase() }
