package com.lightningkite.lightningserver.scim

import com.lightningkite.EmailAddress
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningdb.Description
import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningdb.Modification
import com.lightningkite.lightningdb.SortPart
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
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
    @Serializable(ScimSortPartSerializer::class) val sortBy: SortPart<T>? = null,
    @Description("One-based index of the first result.")
    val startIndex: Int = 1,
    val count: Int = 100,
    val attributes: Set<ScimAttribute> = setOf(),
    val excludedAttributes: Set<ScimAttribute> = setOf(),
)

@ScimSchemaUri("urn:ietf:params:scim:api:messages:2.0:PatchOp")
class ScimModificationSerializer<T>(val subtype: KSerializer<T>) : KSerializer<Modification<T>> {
    override val descriptor: SerialDescriptor
        get() = TODO("Not yet implemented")

    override fun serialize(
        encoder: Encoder,
        value: Modification<T>
    ) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): Modification<T> {
        TODO("Not yet implemented")
    }

}

@Serializable
enum class ScimPatchOpSingleType { add, remove, replace }


/**
 * See https://datatracker.ietf.org/doc/html/rfc7644#autoid-17
 */
class ScimConditionSerializer<T> : KSerializer<Condition<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningdb.Condition/scim", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Condition<T>
    ) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): Condition<T> {
        TODO("Not yet implemented")
    }
}

/**
 * See https://datatracker.ietf.org/doc/html/rfc7644#autoid-17
 */
class ScimSortPartSerializer<T> : KSerializer<SortPart<T>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.lightningdb.SortPart/scim", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: SortPart<T>
    ) {
        TODO("Not yet implemented")
    }

    override fun deserialize(decoder: Decoder): SortPart<T> {
        TODO("Not yet implemented")
    }
}


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
@Serializable data class ScimServiceProviderConfigBulk(val supported: Boolean = true, val maxOperations: Int = 1000, val maxPayloadSize: Int = 100_000)
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
data class ScimEmailAddress(
    val value: EmailAddress,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
data class ScimPhoneNumber(
    val value: String,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
data class ScimIms(
    val type: String,
    val value: String,
)

@Serializable
data class ScimCertificate(
    val value: String
)

@Serializable
data class ScimPhoto(
    val value: String,
    val type: String
)

@Serializable
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
data class ScimRole(
    val value: String,
    val display: String,
    val type: String,
    val primary: Boolean = false,
)

@Serializable
enum class ScimDirect { direct, indirect }

@Serializable
data class ScimUserOrGroupReference(
    val value: String,
    val display: String,
    val type: ScimDirect = ScimDirect.direct,
    @ScimReference(ScimUser::class, ScimGroup::class) @SerialName("\$ref") val ref: String,
)

@Serializable
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
data class ScimUserName(
    val formatted: String? = null,
    val familyName: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val honorificPrefix: String? = null,
    val honorificSuffix: String? = null,
)