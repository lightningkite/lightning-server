package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlin.time.Duration.Companion.minutes

/**
 * Defines a type of authenticated principal (user, service account, etc.).
 *
 * [PrincipalType] is a key abstraction in Lightning Server's authentication system.
 * It defines how to:
 * - Serialize and deserialize subject IDs
 * - Fetch subject data from storage
 * - Cache subject data
 * - Handle masquerading
 * - Look up subjects by properties
 *
 * ## Implementing PrincipalType
 *
 * Typically implemented as a companion object on your subject data class:
 *
 * ```kotlin
 * @Serializable
 * data class User(
 *     override val _id: Uuid,
 *     val email: String,
 *     val name: String
 * ) : HasId<Uuid> {
 *     companion object : PrincipalType<User, Uuid> {
 *         override val idSerializer = Uuid.serializer()
 *         override val subjectSerializer = serializer()
 *         val table = DatabaseTableDefinition<User>()   // define once, reuse everywhere
 *
 *         context(server: ServerRuntime)
 *         override suspend fun fetch(id: Uuid): User {
 *             return database().table(table).get(id)
 *                 ?: throw NotFoundException("User not found")
 *         }
 *     }
 * }
 * ```
 *
 * ## Registration
 *
 * Register principal types in your server builder:
 * ```kotlin
 * object Server : ServerBuilder() {
 *     init {
 *         register(User) // Registers User.Companion as PrincipalType
 *     }
 * }
 * ```
 *
 * @param SUBJECT The type of authenticated entity (must have an ID)
 * @param ID The type of ID for the subject
 */
public interface PrincipalType<SUBJECT : HasId<ID>, ID : Comparable<ID>> {
    /**
     * Serializer for the subject's ID type.
     */
    public val idSerializer: KSerializer<ID>

    /**
     * Serializer for the complete subject type.
     */
    public val subjectSerializer: KSerializer<SUBJECT>

    /**
     * The name of this principal type (defaults to simple class name).
     *
     * Used for principal type identification in authentication tokens.
     */
    public val name: String get() = subjectSerializer.descriptor.serialName.substringAfterLast('.')

    /**
     * Fetches the complete subject data for the given ID.
     *
     * This is called when accessing `Authentication.fetch()` and should typically
     * query your data store. The result is automatically cached.
     *
     * @param id The subject's ID
     * @return The complete subject data
     * @throws NotFoundException if the subject doesn't exist
     */
    context(server: ServerRuntime)
    public suspend fun fetch(id: ID): SUBJECT

    /**
     * Cache key for storing fetched subject data.
     *
     * Subjects are cached for 5 minutes by default and only stored locally
     * (not in distributed caches).
     */
    public val subjectCacheKey: SerializableCache.Key<SUBJECT>
        get() = SerializableCache.Key(
            "$name-subject",
            subjectSerializer,
            expireAfter = 5.minutes,
            localOnly = true
        )

    /**
     * Checks if the subject type has a property with the given name.
     *
     * @param property The property name to check
     * @return `true` if the property exists on the subject type
     */
    public fun hasProperty(property: String): Boolean =
        subjectSerializer.descriptor.getElementIndex(property) != CompositeDecoder.UNKNOWN_NAME

    /**
     * Gets the value of a property from a subject instance as a string.
     *
     * @param principal The subject instance
     * @param property The property name (use "TypeName/_id" for the ID)
     * @return The property value as a string, or null if not found
     */
    context(server: ServerRuntime)
    public fun getProperty(principal: SUBJECT, property: String): String? =
        if (property == "$name/_id") idString(principal._id)
        else server.internalSerialization.formDataFormat.encodeToMap(subjectSerializer, principal)[property]

    /**
     * Normalizes a property value.
     *
     * End user provided values may not always be exactly what your server data expects, and this function is meant to
     * normalize the value to be consistent with data requirements.
     * (e.g., user provided emails may have capitalization in them. This will return the lowercased form of the email.).
     *
     * @param property The property name to normalize by
     * @param value The property value to normalized
     * @return The normalized form of the provided value
     */
    public fun normalizePropertyValue(property: String, value: String): String = value

    /**
     * Fetches a subject by a property value.
     *
     * By default, only supports lookup by ID. Override to support additional properties
     * (e.g., fetching users by email).
     *
     * @param property The property name to search by
     * @param value The property value to match
     * @return The matching subject, or null if not found
     */
    context(server: ServerRuntime)
    public suspend fun fetchByProperty(property: String, value: String): SUBJECT? {
        return when (property) {
            "$name/_id" -> fetch(server.internalSerialization.stringArrayFormat.decodeFromString(idSerializer, value))
            else -> null
        }
    }

    /**
     * Determines if masquerading from one authentication into another is permitted.
     *
     * Override this to implement masquerade authorization logic. For example:
     * ```kotlin
     * context(server: ServerRuntime)
     * override suspend fun permitMasquerade(
     *     from: Authentication<*>,
     *     into: Authentication<User>
     * ): Boolean {
     *     // Only admins can masquerade
     *     return from.meetsRequirements(setOf(RequiredScope("admin")))
     * }
     * ```
     *
     * @param from The original authentication attempting to masquerade
     * @param into The target authentication to masquerade as
     * @return `true` if masquerading is permitted, `false` otherwise
     */
    context(server: ServerRuntime)
    public suspend fun permitMasquerade(
        from: Authentication<*>,
        into: Authentication<SUBJECT>,
    ): Boolean = false

    /**
     * List of cache keys to pre-populate when authentication is created.
     *
     * Override to specify data that should be eagerly loaded with every authentication.
     */
    public val precache: List<AuthCacheKey<SUBJECT, *>> get() = emptyList()

    public companion object;
}

/*
 * TODO: API Recommendations
 *
 * 1. The fetchByProperty method could be more efficient with an index-based lookup system.
 *    Consider adding a registration mechanism for indexed properties:
 *    ```kotlin
 *    val indices = mapOf(
 *        "email" to { email: String -> database().table(userTable).find { it.email eq email }.first() }
 *    )
 *    ```
 *
 * 2. The subjectCacheKey has hard-coded expiration (5 minutes) and localOnly (true).
 *    Consider making these configurable per principal type for different use cases.
 *
 * 3. The hasProperty and getProperty methods use reflection-like behavior through serialization.
 *    For performance-critical paths, consider adding a compile-time code generation approach.
 *
 * 4. The permitMasquerade logic defaults to false (secure by default - good!), but users might
 *    not realize they need to override it. Consider adding a logging statement when masquerade
 *    is attempted but not permitted.
 *
 * 5. The precache list could benefit from a way to conditionally include keys based on scopes or
 *    other authentication properties to avoid loading unnecessary data.
 */

private object PrincipalTypeRegistry : MapRegistryExtension<String, PrincipalType<*, *>>

public fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> ServerBuilder.register(type: PrincipalType<SUBJECT, ID>) {
    val registry = extensions[PrincipalTypeRegistry]
    registry[type.name]?.let {
        if (it.subjectSerializer != type.subjectSerializer) throw DuplicateRegistrationError(
            "Encountered two PrincipalTypes with the same name: ${type.name}",
            it,
            type
        )
        return
    }
    registry.register(type.name, type)
}

public val ServerDefinition.principalTypes: Map<String, PrincipalType<*, *>> by PrincipalTypeRegistry

@Suppress("UNCHECKED_CAST")
context(server: ServerRuntime)
public inline fun <reified SUBJECT : HasId<ID>, ID : Comparable<ID>> principalTypeFor(): PrincipalType<SUBJECT, ID> {
    val name = serializerOrContextual<SUBJECT>().descriptor.serialName
    return server.server.principalTypes.values
        .firstOrNull { it.subjectSerializer.descriptor.serialName == name }
            as? PrincipalType<SUBJECT, ID>
        ?: throw IllegalArgumentException("Principal type for $name not found")
}

