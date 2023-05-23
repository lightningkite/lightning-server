@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningserver.db.testmodels

import com.lightningkite.lightningdb.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.*


@DatabaseModel
@Serializable
data class TestThing(override val _id: UUID = UUID.randomUUID(), val value: Int = 0) : HasId<UUID>

@DatabaseModel
@Serializable
data class TempThing(override val _id: Int) : HasId<Int>

@DatabaseModel
@Serializable
data class UniqueFieldClass(
    override val _id: Int,
    @Unique val unique1: Int,
) : HasId<Int>

@DatabaseModel
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2"])
data class UniqueSetClass(
    override val _id: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
) : HasId<Int>

@DatabaseModel
@Serializable
@UniqueSet(["uniqueSet1", "uniqueSet2"])
data class UniqueComboClass(
    override val _id: Int,
    @Unique val unique1: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
) : HasId<Int>

@DatabaseModel
@Serializable
@UniqueSetJankPatch(["uniqueSet1", "uniqueSet2", ":", "uniqueSet3", "uniqueSet4"])
data class UniqueSetJankClass(
    override val _id: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
    val uniqueSet3: Int,
    val uniqueSet4: Int,
) : HasId<Int>

@DatabaseModel
@Serializable
@UniqueSetJankPatch(["uniqueSet1", "uniqueSet2", ":", "uniqueSet3", "uniqueSet4"])
data class UniqueComboJankClass(
    override val _id: Int,
    @Unique val unique1: Int,
    val uniqueSet1: Int,
    val uniqueSet2: Int,
    val uniqueSet3: Int,
    val uniqueSet4: Int,
) : HasId<Int>