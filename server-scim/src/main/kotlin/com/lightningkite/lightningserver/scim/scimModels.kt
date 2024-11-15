package com.lightningkite.lightningserver.scim

import com.lightningkite.EmailAddress
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Modification
import com.lightningkite.lightningdb.Query
import com.lightningkite.lightningdb.SortPart
import com.lightningkite.lightningdb.toModification
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathAccess
import com.lightningkite.serialization.DataClassPathNotNull
import com.lightningkite.serialization.DataClassPathSelf
import com.lightningkite.serialization.PartialSerializer
import com.lightningkite.serialization.SerializableProperty
import com.lightningkite.serialization.innerElement
import com.lightningkite.serialization.nullElement
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.KClass


@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ScimSchemaUri(val uri: String)

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class ScimExtension(val uri: String)

val KSerializer<*>.scimSchemaUri: String
    get() = descriptor.annotations.filterIsInstance<ScimSchemaUri>().first().uri

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class ScimReadOnly

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class ScimWriteOnly

@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class ScimReference(vararg val types: KClass<*>)

@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:ListResponse")
@Serializable
data class ScimListResponse<T>(
    val totalResults: Int,
    val Resources: List<T>,
    @Description("One-based index of the first result.")
    val startIndex: Int,
    val itemsPerPage: Int,
)

@Serializable
@JvmInline
value class ScimAttribute(val string: String) {
    val name: String get() = string.substringAfterLast(':')
    val namespace: String get() = string.substringBeforeLast(':', "")
}

@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:SearchRequest")
@Serializable
data class ScimQuery<T>(
    @Serializable(ScimConditionSerializer::class) val filter: Condition<T> = Condition.Always,
    val sortBy: ScimPathPartial<T>? = null,
    val sortOrder: ScimSortOrder = ScimSortOrder.ascending,
    @Description("One-based index of the first result.")
    val startIndex: Int = 1,
    val count: Int = 100,
    val attributes: Set<ScimAttribute> = setOf(),
    val excludedAttributes: Set<ScimAttribute> = setOf(),
) {
    fun query(): Query<T> = Query(
        condition = filter,
        orderBy = sortBy?.let {
            it as ScimPath<T, *>
            listOf(SortPart(
                it.dcp,
                ascending = sortOrder == ScimSortOrder.ascending,
                ignoreCase = it.serializer.descriptor.serialName.substringBefore('/') == "kotlin.String"
            ))
        } ?: listOf(),
        skip = startIndex - 1,
        limit = count
    )
}

@Serializable
enum class ScimSortOrder { ascending, descending }

@Serializable
@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:PatchOp")
data class ScimPatchOp<T>(
    val Operations: List<ScimPatchOpSingle<T>>
)

@Serializable
data class ScimPatchOpSingle<T>(
    val type: PatchOpType,
    val path: ScimPathPartial<T>? = null,
    val value: JsonElement = JsonNull,
) {
    fun modification(inner: KSerializer<T>) = when(type) {
        PatchOpType.add -> (path as? ScimPath<T, *> ?: ScimPath.Base(inner)).add(value)
        PatchOpType.remove -> (path as? ScimPath<T, *> ?: ScimPath.Base(inner)).remove()
        PatchOpType.replace -> (path as? ScimPath<T, *> ?: ScimPath.Base(inner)).replace(value)
    }
}

@Serializable(ScimPathPartialSerializer::class)
sealed interface ScimPathPartial<T>
sealed interface ScimPath<T, B>: ScimPathPartial<T> {
    val serializer: KSerializer<B>
    fun toStringOrNull(): String?
    val dcp: DataClassPath<T, B>
    fun add(value: JsonElement): Modification<T> {
        val v = Serialization.jsonScim.decodeFromJsonElement(serializer, value)
        @Suppress("UNCHECKED_CAST")
        return wrap(when(serializer.descriptor.serialName) {
            ListSerializer(Int.serializer()).descriptor.serialName -> Modification.ListAppend<Any?>(v as List<Any?>) as Modification<B>
            SetSerializer(Int.serializer()).descriptor.serialName -> Modification.SetAppend<Any?>(v as Set<Any?>) as Modification<B>
            else -> Modification.Assign(v)
        })
    }
    fun remove(): Modification<T> = wrap(when(serializer.descriptor.serialName) {
        ListSerializer(Int.serializer()).descriptor.serialName -> Modification.ListRemove<Any?>(Condition.Always) as Modification<B>
        SetSerializer(Int.serializer()).descriptor.serialName -> Modification.SetRemove<Any?>(Condition.Always) as Modification<B>
        else -> throw BadRequestException("Cannot remove a non-null field.")
    })
    fun replace(value: JsonElement): Modification<T> {
        val v = Serialization.jsonScim.decodeFromJsonElement(serializer, value)
        return wrap(Modification.Assign(v))
    }
    fun wrap(m: Modification<B>): Modification<T>
    class Base<T>(override val serializer: KSerializer<T>): ScimPath<T, T> {
        override fun equals(other: Any?): Boolean = other is Base<*>
        override fun hashCode(): Int = 0
        override fun toString() = ""
        override fun toStringOrNull(): String? = null
        override val dcp: DataClassPath<T, T> = DataClassPathSelf(serializer)
        //Todo: not quite accurate - needs to add per attribute
        override fun wrap(m: Modification<T>): Modification<T> = m
        override fun add(value: JsonElement): Modification<T> = Serialization.jsonScim.decodeFromJsonElement(PartialSerializer(serializer), value).toModification(serializer)
        override fun remove(): Modification<T> = throw ScimErrorException(ScimError(ScimErrorType.noTarget, "", "400"))
        override fun replace(value: JsonElement): Modification<T> = Serialization.jsonScim.decodeFromJsonElement(PartialSerializer(serializer), value).toModification(serializer)
    }
    data class Field<T, A, B>(val base: ScimPath<T, A>, val field: SerializableProperty<A, B>): ScimPath<T, B> {
        override val serializer: KSerializer<B> = field.serializer
        override val dcp: DataClassPath<T, B> = DataClassPathAccess(base.dcp, field)
        override fun toString() = toStringOrNull()
        override fun toStringOrNull(): String = listOfNotNull(base.toStringOrNull(), field.name /*TODO: Scim name*/).joinToString(".")
        override fun wrap(m: Modification<B>): Modification<T> = base.wrap(Modification.OnField(field, m))
    }
    data class FieldNullable<T, A, B>(val base: ScimPath<T, A>, val field: SerializableProperty<A, B?>): ScimPath<T, B> {
        @Suppress("UNCHECKED_CAST")
        override val serializer: KSerializer<B> = field.serializer.nullElement() as KSerializer<B>
        override val dcp: DataClassPath<T, B> = DataClassPathNotNull(DataClassPathAccess(base.dcp, field))
        override fun toString() = toStringOrNull()
        override fun toStringOrNull(): String = listOfNotNull(base.toStringOrNull(), field.name /*TODO: Scim name*/).joinToString(".")
        override fun wrap(m: Modification<B>): Modification<T> {
            return if(m is Modification.Assign) base.wrap(Modification.OnField(field, Modification.Assign(m.value)))
            else base.wrap(Modification.OnField(field, Modification.IfNotNull(m)))
        }
        override fun remove(): Modification<T> = when(serializer.descriptor.serialName) {
            ListSerializer(Int.serializer()).descriptor.serialName,
            SetSerializer(Int.serializer()).descriptor.serialName -> super.remove()
            else -> base.wrap(Modification.OnField(field, Modification.Assign(null)))
        }
    }
    data class Filter<T, C: Collection<A>, A>(val base: ScimPath<T, C>, val condition: Condition<A>): ScimPath<T, A> {
        @Suppress("UNCHECKED_CAST")
        override val serializer: KSerializer<A> = base.serializer.innerElement()!! as KSerializer<A>
        override val dcp: DataClassPath<T, A> = throw IllegalArgumentException("Cannot sort by multivalue attribute")
        override fun toString() = toStringOrNull()
        override fun toStringOrNull(): String = "${base}[$condition]"
        override fun wrap(m: Modification<A>): Modification<T> {
            @Suppress("UNCHECKED_CAST")
            return when(serializer.descriptor.serialName) {
                ListSerializer(Int.serializer()).descriptor.serialName -> Modification.ListPerElement<A>(condition, m)
                SetSerializer(Int.serializer()).descriptor.serialName -> Modification.SetPerElement<A>(condition, m)
                else -> throw IllegalStateException()
            } as Modification<T>
        }
    }
}

@Serializable enum class PatchOpType { add, remove, replace }


@Serializable
@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:BulkRequest")
data class ScimBulkRequest(
    val failOnErrors: Int = 0,
    val Operations: List<ScimBulkRequestOperation>
)

@Serializable
data class ScimBulkRequestOperation(
    val method: ScimBulkRequestOperationMethod,
    val bulkId: String,
    val version: String? = null,
    val path: String,
    val data: JsonElement? = null,
)

@Serializable
enum class ScimBulkRequestOperationMethod {
    POST, PUT, PATCH, DELETE
}

@Serializable
@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:BulkResponse")
data class ScimBulkResponse(
    val bulkId: String,
    val location: String? = null,
    val response: JsonElement? = null,
    val status: String,
)

@Serializable
@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:Error")
data class ScimError(
    val scimType: ScimErrorType,
    val detail: String,
    val status: String,
)

@Serializable
enum class ScimErrorType {
    invalidFilter,
    tooMany,
    uniqueness,
    mutability,
    invalidSyntax,
    invalidPath,
    noTarget,
    invalidValue,
    invalidVers,
    sensitive
}

@Serializable
@ScimSchemaUri("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig")
data class ScimServiceProviderConfig(
    val documentationUri: String = generalSettings().publicUrl + "/scim/documentation",
    val patch: ScimServiceProviderConfigPatch = ScimServiceProviderConfigPatch(),
    val bulk: ScimServiceProviderConfigBulk = ScimServiceProviderConfigBulk(),
    val filter: ScimServiceProviderConfigFilter = ScimServiceProviderConfigFilter(),
    val changePassword: ScimServiceProviderConfigChangePassword = ScimServiceProviderConfigChangePassword(),
    val sort: ScimServiceProviderConfigSort = ScimServiceProviderConfigSort(),
    val etag: ScimServiceProviderConfigEtag = ScimServiceProviderConfigEtag(),
    val authenticationSchemes: Set<ScimServiceProviderConfigAuthenticationScheme> = setOf()
)

@Serializable data class ScimServiceProviderConfigPatch(val supported: Boolean = true)
@Serializable data class ScimServiceProviderConfigBulk(val supported: Boolean = false, val maxOperations: Int = 1000, val maxPayloadSize: Int = 100_000)
@Serializable data class ScimServiceProviderConfigFilter(val supported: Boolean = true, val maxResults: Int = 1000)
@Serializable data class ScimServiceProviderConfigChangePassword(val supported: Boolean = true)
@Serializable data class ScimServiceProviderConfigSort(val supported: Boolean = true)
@Serializable data class ScimServiceProviderConfigEtag(val supported: Boolean = true)
@Serializable data class ScimServiceProviderConfigAuthenticationScheme(
    val type: ScimServiceProviderConfigAuthenticationSchemeType,
    val name: String,
    val description: String,
    val specUri: String? = null,
    val documentationUri: String? = null,
)
@Serializable enum class ScimServiceProviderConfigAuthenticationSchemeType { oauth, oauth2, oauthbearertoken, httpbasic, httpdigest }

interface ScimResource<ID: Comparable<ID>>: HasId<ID> {
    val externalId: String?
    val createdAt: Instant
    val modifiedAt: Instant
}

//@Serializable
//data class ScimMeta(
//    val resourceType: String,  // calculated
//    val created: Instant,
//    val lastModified: Instant,
//    val location: String,
//    val version: String? = null,
//)

@Serializable
@GenerateDataClassPaths
@ScimSchemaUri("urn:ietf:params:scim:schemas:core:2.0:Schema")
data class ScimSchema(
    override val _id: String,
    override val externalId: String? = null,
    override val createdAt: Instant,
    override val modifiedAt: Instant,
    val name: String,
    val description: String,
    val attributes: Set<ScimAttributeDefinition>,
): ScimResource<String>



@ScimSchemaUri("urn:ietf:params:scim:schemas:core:2.0:ResourceType")
@Serializable
@GenerateDataClassPaths
data class ScimResourceType(
    override val _id: String,
    override val externalId: String? = null,
    override val createdAt: Instant,
    override val modifiedAt: Instant,
    val name: String,
    val description: String? = null,
    /**
     * Relative URL
     */
    val endpoint: String,
    val schema: String,
    val schemaExtensions: List<ScimSchemaExtension>? = null,
): ScimResource<String>
@Serializable data class ScimSchemaExtension(val schema: String, val required: Boolean)

@Serializable
@GenerateDataClassPaths
data class ScimAttributeDefinition(
    val name: String,
    val type: ScimType,
    val subAttributes: Set<ScimAttributeDefinition>? = null,
    val multiValued: Boolean,
    val description: String,
    val required: Boolean,
    val canonicalValues: List<String>? = null,
    val caseExact: Boolean = true,
    val mutability: ScimMutability = ScimMutability.readWrite,
    val returned: ScimReturned = ScimReturned.default,
    val uniqueness: ScimUniqueness = ScimUniqueness.none,
    val referenceTypes: Set<String>? = null,
)

@Serializable
enum class ScimUniqueness { none, server, global }

@Serializable
enum class ScimReturned { always, never, default, request }

@Serializable
enum class ScimMutability { readOnly, readWrite, immutable, writeOnly }

@Serializable
enum class ScimType { string, boolean, decimal, integer, dateTime, reference, complex }

@Serializable
@GenerateDataClassPaths
@ScimSchemaUri("urn:ietf:params:scim:schemas:core:2.0:User")
data class ScimUser(
    override val _id: String,
    override val externalId: String? = null,
    override val createdAt: Instant,
    override val modifiedAt: Instant,
    val userName: String,
    val name: ScimUserName,
    val displayName: String,
    val nickName: String,
    val profileUrl: String? = null,
    val title: String? = null,
    val userType: String? = null,
    val preferredLanguage: String? = null,
    val locale: String? = null,
    val timezone: TimeZone? = null,
    val active: Boolean = true,
    val password: String = "",
    val emails: Set<ScimEmailAddress> = setOf(),
    val phoneNumbers: Set<ScimPhoneNumber> = setOf(),
    val ims: Set<ScimIms> = setOf(),
    val photos: Set<ScimPhoto> = setOf(),
    val addresses: Set<ScimAddress> = setOf(),
    @ScimReadOnly val groups: Set<ScimUserOrGroupReference> = setOf(),
    val entitlements: Set<ScimRole> = setOf(),
    val roles: Set<ScimRole> = setOf(),
    val x509Certificates: Set<ScimCertificate> = setOf(),
    val enterprise: ScimUserEnterprise? = null,
): ScimResource<String>

@Serializable
@GenerateDataClassPaths
data class ScimEmailAddress(
    val value: EmailAddress,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
@GenerateDataClassPaths
data class ScimPhoneNumber(
    val value: String,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
@GenerateDataClassPaths
data class ScimIms(
    val type: String,
    val value: String,
)

@Serializable
@GenerateDataClassPaths
data class ScimCertificate(
    val value: String
)

@Serializable
@GenerateDataClassPaths
data class ScimPhoto(
    val value: String,
    val type: String
)

@Serializable
@GenerateDataClassPaths
@ScimExtension("urn:ietf:params:scim:schemas:extension:enterprise:2.0:User")
data class ScimUserEnterprise(
    val employeeNumber: String? = null,
    val costCenter: String? = null,
    val organization: String? = null,
    val division: String? = null,
    val department: String? = null,
    val manager: ScimUserOrGroupReference? = null,
)

@Serializable
@GenerateDataClassPaths
@ScimSchemaUri("urn:ietf:params:scim:schemas:core:2.0:Group")
data class ScimGroup(
    override val _id: String,
    override val externalId: String? = null,
    override val createdAt: Instant,
    override val modifiedAt: Instant,
    val displayName: String,
    val members: Set<ScimUserOrGroupReference> = setOf()
): ScimResource<String>

@Serializable
@GenerateDataClassPaths
data class ScimRole(
    val value: String,
    val display: String,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
enum class ScimDirect { direct, indirect }

@Serializable
@GenerateDataClassPaths
data class ScimUserOrGroupReference(
    val value: String,
    val display: String,
    val type: ScimDirect = ScimDirect.direct,
    @ScimReference(ScimUser::class, ScimGroup::class) @SerialName("\$ref") val ref: String,
)

@Serializable
@GenerateDataClassPaths
data class ScimAddress(
    val formatted: String,
    val streetAddress: String,
    val locality: String,
    val region: String,
    val postalCode: String,
    val country: String,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
@GenerateDataClassPaths
data class ScimUserName(
    val formatted: String? = null,
    val familyName: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val honorificPrefix: String? = null,
    val honorificSuffix: String? = null,
)