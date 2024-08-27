@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.testmodels

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.uuid


@GenerateDataClassPaths
@Serializable
data class TestThing(override val _id: UUID = uuid(), val value: Int = 0) : HasId<UUID>

@GenerateDataClassPaths
@Serializable
data class TempThing(override val _id: Int) : HasId<Int>

@GenerateDataClassPaths
@Serializable
data class UniqueFieldClass(
    override val _id: Int,
    @Unique val unique1: Int,
) : HasId<Int>

@GenerateDataClassPaths
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2"])
data class UniqueSetClass(
    override val _id: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
) : HasId<Int>

@GenerateDataClassPaths
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2"])
data class UniqueComboClass(
    override val _id: Int,
    @Unique val unique1: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
) : HasId<Int>

@GenerateDataClassPaths
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2",])
@UniqueSet(["uniqueSet3", "uniqueSet4"])
data class UniqueSetJankClass(
    override val _id: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
    val uniqueSet3: Int,
    val uniqueSet4: Int,
) : HasId<Int>

@GenerateDataClassPaths
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2"])
@UniqueSet(["uniqueSet3", "uniqueSet4"])
data class UniqueComboJankClass(
    override val _id: Int,
    @Unique val unique1: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
    val uniqueSet3: Int,
    val uniqueSet4: Int,
) : HasId<Int>