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
    val models: Map<String, LightningServerKSchemaModel>,
)

@Serializable
data class LightningServerKSchemaModel(
    val collectionName: String,
    val type: VirtualTypeReference,
    val path: String,
    val searchFields: List<String>,
    val tableColumns: List<String>,
    val titleFields: List<String>,
)

@Serializable
data class LightningServerKSchemaEndpoint(
    val group: String? = null,
    val method: String,
    val path: String,
    val routes: Map<String, VirtualTypeReference>,
    val input: VirtualTypeReference,
    val output: VirtualTypeReference,
)