package com.lightningkite.lightningserver.demo.models

import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import kotlin.uuid.Uuid

/**
 * User model for authentication and authorization.
 * 
 * This model demonstrates:
 * - HasId interface for database primary key
 * - HasEmail interface for email-based authentication
 * - HasPassword interface for password hashing
 * - Queryable for type-safe query DSL
 */
@Queryable
data class User(
    override val _id: Uuid = Uuid.random(),
    override val email: String,
    override val hashedPassword: String = "",
    val displayName: String = "",
    val isSuperUser: Boolean = false,
    val bio: String? = null,
) : HasId<Uuid>, HasEmail, HasPassword
