package com.lightningkite.lightningserver.demo.models

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * User model for authentication and authorization.
 * 
 * This model demonstrates:
 * - HasId interface for database primary key
 * - HasEmail interface for email-based authentication
 * - HasPassword interface for password hashing
 * - GenerateDataClassPaths for type-safe query DSL
 */
@Serializable
@GenerateDataClassPaths
data class User(
    override val _id: Uuid = Uuid.random(),
    override val email: String,
    override val hashedPassword: String = "",
    val displayName: String = "",
    val isSuperUser: Boolean = false,
    val bio: String? = null,
) : HasId<Uuid>, HasEmail, HasPassword
