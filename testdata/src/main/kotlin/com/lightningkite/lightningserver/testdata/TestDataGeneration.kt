package com.lightningkite.lightningserver.testdata

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

@JvmInline
public value class TypeID(public val tableName: String)

public class TestDataGeneration(public val rng: Random) {
    public class ForModel<T : HasId<ID>, ID : Comparable<ID>> {
        public val generated: ArrayList<T> = ArrayList()


    }
}

public class ModelTestDataEndpoints<T : HasId<ID>, ID : Comparable<ID>>(
    public val info: ModelInfo<*, T, ID>,
    private val generate: suspend context(ServerRuntime) TestDataGeneration.() -> T
) {
    context(runtime: ServerRuntime, testData: TestDataGeneration)
    public fun generate(): Flow<T> = flow {
        while (true) emit(generate(testData))
    }
}