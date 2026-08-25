package com.lightningkite.lightningserver.testdata

import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlin.random.Random
import kotlin.uuid.Uuid

public data class TestDataGeneration<T : HasId<ID>, ID : Comparable<ID>>(
    val info: ModelInfo<*, T, ID>,
    val seed: Int,
    val genId: GenId = GenId.random(),
) {
    val serializer: KSerializer<T> get() = info.serializer

    @JvmInline
    public value class GenId private constructor(public val long: Long) {
        public companion object {
            private const val MASK: Long = 0xFFFFFFFF

            public fun random(rng: Random = Random.Default): GenId = GenId(rng.nextLong() and MASK)

            public fun fromUntruncatedLong(long: Long): GenId = GenId(long and MASK)
        }
    }

    override fun hashCode(): Int = seed + (31 * info.tableName.hashCode()) + (7 * genId.long.hashCode())
    override fun equals(other: Any?): Boolean = other is TestDataGeneration<*, *> && other.hashCode() == this.hashCode()

    public val rng: Random by lazy { Random(this.hashCode()) }

    public data class Result<T : HasId<ID>, ID : Comparable<ID>>(
        val request: TestDataGeneration<T, ID>,
        val generated: T,
        val sideEffects: List<Result<*, *>>
    )
}

/**
 * Marker written into the top 4 bits of every [seeded] UUID's most significant
 * long, flagging the UUID as deterministically-generated test data rather than
 * a real, randomly-generated identifier.
 */
private const val TEST_DATA_FLAG: Long = 0xDL

/**
 * Deterministically derives a [Uuid] from the ambient [TestDataGeneration] context.
 *
 * The most significant bits are laid out as:
 * - bits 63-60: [TEST_DATA_FLAG], marking this as generated test data
 * - bits 59-0: [TestDataGeneration.genId], zero-extended to 60 bits
 *
 * This leaves the iteration number plainly visible at a glance, with no pseudo-random bits mixed
 * into the msb to obscure it. The least significant bits are entirely pseudo-random filler from
 * [TestDataGeneration.rng].
 *
 * Because the flag and iteration number are embedded directly in the UUID, any UUID produced
 * this way can be inspected to confirm it's test data and to recover the iteration that produced
 * it. Combined with the type and starting seed, calling [seeded] again in the same order will
 * reproduce the exact same UUID, and thus the exact same generated model.
 *
 * For example, `with(TestDataGeneration(typeName = "Post", iteration = 3, seed = 42)) { Uuid.seeded() }`
 * always produces `d0000000-0000-0003-2a42-0372317fed8d`. Splitting the most significant bits
 * `d000000000000003` into its fields: `d` is [TEST_DATA_FLAG] and `000000000000003` is the
 * iteration (`3`).
 */
context(generation: TestDataGeneration<T, ID>)
public fun <T : HasId<ID>, ID : Comparable<ID>> Uuid.Companion.seeded(): Uuid {
    val msb = (TEST_DATA_FLAG shl 60) or generation.genId.long
    val lsb = generation.rng.nextLong()
    return Uuid.fromLongs(msb, lsb)
}
