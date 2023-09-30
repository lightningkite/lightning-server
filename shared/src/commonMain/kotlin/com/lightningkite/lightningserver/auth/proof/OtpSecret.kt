@file:UseContextualSerialization(Duration::class)
package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.ExperimentalLightningServer
import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.lightningdb.HasId
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
    val algorithm: OtpHashAlgorithm
) : HasId<ID>
