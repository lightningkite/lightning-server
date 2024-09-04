package com.lightningkite.lightningserver.schema

import com.lightningkite.serialization.VirtualEnum
import com.lightningkite.serialization.VirtualStruct
import com.lightningkite.serialization.VirtualTypeReference
import kotlinx.serialization.Serializable


@Serializable
data class LightningServerKSchema(
    val baseUrl: String,
    val baseWsUrl: String,
    val structures: Map<String, VirtualStruct>,
    val enums: Map<String, VirtualEnum>,
    val endpoints: List<LightningServerKSchemaEndpoint>,
    val interfaces: List<LightningServerKSchemaInterface>,
)

@Serializable
data class LightningServerKSchemaInterface(
    val matches: VirtualTypeReference,
    val path: String,
)

@Serializable
data class LightningServerKSchemaEndpoint(
    val group: String? = null,
    val description: String,
    val summary: String,
    val method: String,
    val path: String,
    val routes: Map<String, VirtualTypeReference>,
    val input: VirtualTypeReference,
    val output: VirtualTypeReference,
    val docGroup: String?,
    val belongsToInterface: VirtualTypeReference?,
)