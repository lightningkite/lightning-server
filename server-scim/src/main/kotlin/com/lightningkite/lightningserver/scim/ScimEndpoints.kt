package com.lightningkite.lightningserver.scim

import com.lightningkite.CaselessStringSerializer
import com.lightningkite.EmailAddressSerializer
import com.lightningkite.LowercaseOnSerialize
import com.lightningkite.TrimLowercaseOnSerialize
import com.lightningkite.TrimmedCaselessStringSerializer
import com.lightningkite.lightningdb.CollectionChanges
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.FieldCollection
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.InMemoryFieldCollection
import com.lightningkite.lightningdb.Modification
import com.lightningkite.lightningdb.SortPart
import com.lightningkite.lightningdb.Unique
import com.lightningkite.lightningdb.get
import com.lightningkite.lightningdb.insertOne
import com.lightningkite.lightningserver.auth.AuthOptions
import com.lightningkite.lightningserver.auth.authRequired
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathGroup
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.db.ModelSerializationInfo
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.ForbiddenException
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.routes.docName
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.typed.AuthAccessor
import com.lightningkite.lightningserver.typed.api
import com.lightningkite.lightningserver.typed.arg
import com.lightningkite.lightningserver.typed.delete
import com.lightningkite.lightningserver.typed.get
import com.lightningkite.lightningserver.typed.patch
import com.lightningkite.lightningserver.typed.path1
import com.lightningkite.lightningserver.typed.put
import com.lightningkite.serialization.description
import com.lightningkite.serialization.descriptionOrDisplayName
import com.lightningkite.serialization.displayName
import com.lightningkite.serialization.listElement
import com.lightningkite.serialization.serializableProperties
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement

class ScimEndpoints<Auth : HasId<*>>(
    path: ServerPath,
    val userInfo: ModelInfo<Auth, ScimUser, String>,
    val groupInfo: ModelInfo<Auth, ScimGroup, String>,
    val otherResources: Collection<ModelInfo<Auth, *, *>>
) : ServerPathGroup(path) {
    init {
        path.docName = "SCIM"
    }

    val users = ScimResourceEndpoints<Auth, ScimUser, String>(path("Users"), userInfo)
    val groups = ScimResourceEndpoints<Auth, ScimGroup, String>(path("Groups"), groupInfo)
    val others = otherResources.map {
        assert(it.serialization.serializer.descriptor.annotations.any { it is ScimSchemaUri }) {
            "You can only use models that have the ScimSchemaUri annotation."
        }
        ScimResourceEndpoints(path(it.collectionName), it)
    }
    val me = path("Me")  // redirect to actual SCIM resource
    val config = path("ServiceProviderConfig").get.api(
        authOptions = noAuth,
        summary = "Get SCIM Service Provider Config",
        implementation = { _: Unit -> ScimServiceProviderConfig() }
    )
    val types = ScimResourceEndpoints<Auth, ScimResourceType, String>(
        path("ResourceTypes"),
        object : ModelInfo<Auth, ScimResourceType, String> {
            override val serialization: ModelSerializationInfo<ScimResourceType, String> = ModelSerializationInfo()
            override val authOptions: AuthOptions<Auth> = userInfo.authOptions
            override fun registerChangeListener(action: suspend (CollectionChanges<ScimResourceType>) -> Unit) {}
            val collection = InMemoryFieldCollection(
                listOf(
                users.resourceType,
                groups.resourceType,
            ).plus(others.map { it.resourceType }).toMutableList(), ScimResourceType.serializer()
            )

            override fun baseCollection(): FieldCollection<ScimResourceType> = collection
            override fun collection(): FieldCollection<ScimResourceType> = collection
            override suspend fun collection(auth: AuthAccessor<Auth>): FieldCollection<ScimResourceType> = collection
        })
    val schemas = ScimResourceEndpoints<Auth, ScimSchema, String>(
        path("Schemas"),
        object : ModelInfo<Auth, ScimSchema, String> {
            override val serialization: ModelSerializationInfo<ScimSchema, String> = ModelSerializationInfo()
            override val authOptions: AuthOptions<Auth> = userInfo.authOptions
            override fun registerChangeListener(action: suspend (CollectionChanges<ScimSchema>) -> Unit) {}
            val collection = InMemoryFieldCollection(
                listOf(
                users.schema,
                groups.schema,
            ).plus(others.map { it.schema }).toMutableList(), ScimSchema.serializer()
            )

            override fun baseCollection(): FieldCollection<ScimSchema> = collection
            override fun collection(): FieldCollection<ScimSchema> = collection
            override suspend fun collection(auth: AuthAccessor<Auth>): FieldCollection<ScimSchema> = collection
        })
    val bulk = path("Bulk").post.api(
        authOptions = userInfo.authOptions,
        summary = "SCIM Bulk",
        implementation = { bulk: ScimBulkRequest ->

            ScimBulkResponse(TODO(), status = TODO())
        }
    )
}


class ScimResourceEndpoints<USER : HasId<*>?, T : HasId<ID>, ID : Comparable<ID>>(
    path: ServerPath,
    val info: ModelInfo<USER, T, ID>
) : ServerPathGroup(path) {
    val schema: ScimSchema = ScimSchema(
        _id = info.serialization.serializer.scimSchemaUri,
        externalId = info.serialization.serializer.descriptor.serialName,
        createdAt = Instant.fromEpochMilliseconds(0L),
        modifiedAt = Instant.fromEpochMilliseconds(0L),
        name = info.serialization.serializer.displayName,
        description = info.serialization.serializer.description ?: info.serialization.serializer.displayName,
        attributes = info.serialization.serializer.attributes()
    )
    val resourceType: ScimResourceType = ScimResourceType(
        _id = info.serialization.serializer.descriptor.serialName,
        createdAt = Instant.fromEpochMilliseconds(0L),
        modifiedAt = Instant.fromEpochMilliseconds(0L),
        name = info.serialization.serializer.displayName,
        description = info.serialization.serializer.description,
        endpoint = "/" + path.segments.last().toString(),
        schema = schema._id
    )

    val create = post.api(
        summary = "Scim Create",
        authOptions = info.authOptions,
        inputType = info.serialization.serializer,
        outputType = info.serialization.serializer,
        implementation = {
            info.collection(this).insertOne(it) ?: throw ForbiddenException("Insert was not permitted.")
        }
    )
    val detail = path.arg("id", info.serialization.idSerializer)
    val retrieveKnown = detail.get.api(
        summary = "Scim Retrieve Known",
        authOptions = info.authOptions,
        inputType = Unit.serializer(),
        outputType = info.serialization.serializer,
        implementation = {
            info.collection(this).get(path1)!!
        }
    )
    val query = get.api(
        summary = "Scim Query",
        authOptions = info.authOptions,
        inputType = ScimQuery.serializer(info.serialization.serializer),
        outputType = ScimListResponse.serializer(info.serialization.serializer),
        implementation = { TODO() }
    )
    val queryPost = post(".query").api(
        summary = "Scim Query Post",
        authOptions = info.authOptions,
        inputType = ScimQuery.serializer(info.serialization.serializer),
        outputType = ScimListResponse.serializer(info.serialization.serializer),
        implementation = { TODO() }
    )
    val replace = detail.put.api(
        summary = "Scim Replace",
        authOptions = info.authOptions,
        inputType = ScimQuery.serializer(info.serialization.serializer),
        outputType = ScimListResponse.serializer(info.serialization.serializer),
        implementation = { TODO() }
    )
    val modify = detail.patch.api(
        summary = "Scim Modify",
        authOptions = info.authOptions,
        inputType = ScimPatchOp.serializer(info.serialization.serializer),
        outputType = ScimListResponse.serializer(info.serialization.serializer),
        implementation = { TODO() }
    )
    val remove = detail.delete.api(
        summary = "Scim Delete",
        authOptions = info.authOptions,
        inputType = Unit.serializer(),
        outputType = Unit.serializer(),
        implementation = { TODO() }
    )
}
