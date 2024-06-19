@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.ExperimentalLightningServer
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlin.time.Duration

@OptIn(ExperimentalLightningServer::class)
@Serializable
@GenerateDataClassPaths
data class OtpSecret<ID : Comparable<ID>>(
    @Contextual override val _id: ID,
    val secretBase32: String,
    val label: String,
    val issuer: String,
    val period: Duration,
    val digits: Int,
    val algorithm: OtpHashAlgorithm,
    val active: Boolean = true,
    val establishedAt: Instant = now()
) : HasId<ID>

@Serializable
@GenerateDataClassPaths
data class PasswordSecret<ID : Comparable<ID>>(
    @Contextual override val _id: ID,
    val hash: String,
    val hint: String? = null,
    val establishedAt: Instant = now()
) : HasId<ID>

@Serializable
data class SecretMetadata(
    val establishedAt: Instant = now(),
    val label: String,
)

@Serializable
data class EstablishPassword(
    val password: String,
    val hint: String? = null
)

@Serializable
data class EstablishOtp(
    val label: String? = null
)