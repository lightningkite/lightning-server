package com.lightningkite.lightningdb

import com.lightningkite.serialization.DataClassPath
import com.lightningkite.serialization.DataClassPathSelf
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumComparisonTests {
    @Serializable
    enum class State { First, Second, Third, Fourth, Fifth, Sixth, Seventh, Eighth, Ninth, Tenth }
    private val path = DataClassPathSelf(State.serializer())

    private fun testComparison(
        condition: (DataClassPath<State, State>, State) -> Condition<State>,
        operation: (State, State) -> Boolean
    ) {
        for (state in State.entries) {
            val c = condition(path, state)
            assertTrue { c is Condition.Inside }
            for (against in State.entries) assertEquals(operation(state, against), c(against), "State: $state Against: $against Condition: $c")
        }
    }

    @Test
    fun greaterThan() {
        testComparison(
            condition = { path, state -> path gt state },
            operation = { state, against -> against > state }
        )
    }

    @Test
    fun greaterThanOrEqualTo() {
        testComparison(
            condition = { path, state -> path gte state },
            operation = { state, against -> against >= state }
        )
    }

    @Test
    fun lessThan() {
        testComparison(
            condition = { path, state -> path lt state },
            operation = { state, against -> against < state }
        )
    }

    @Test
    fun lessThanOrEqualTo() {
        testComparison(
            condition = { path, state -> path lte state },
            operation = { state, against -> against <= state }
        )
    }
}