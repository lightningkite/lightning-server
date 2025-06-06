package com.lightningkite.lightningserver.sdk

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.typed.Documentable
import com.lightningkite.lightningserver.typed.SDK2.endpointsByGroup
import kotlinx.serialization.builtins.serializer
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.test.Test

class FormatTests {
    @Test fun testManualImports() {
        val interfaces = listOf(
            Documentable.InterfaceInfo(
                ServerPath.root,
                "TestInterface",
                listOf(Int.serializer()),
                "com.lightningkite.notrealimport"
            )
        )

        buildString {
            listOf(
                "com.lightningkite.*",
                "com.lightningkite.lightningdb.*",
                "com.lightningkite.kiteui.*",
                "kotlinx.datetime.*",
                "com.lightningkite.serialization.*",
                "com.lightningkite.lightningserver.db.*",
                "com.lightningkite.lightningserver.auth.*",
                "kotlinx.serialization.builtins.*",
                "kotlinx.serialization.*",
            )
                .plus(
                    interfaces.mapNotNull { it.import }
                )
                .toSet()
                .joinTo(this, separator = "\n") { "import $it" }
        }.also { println(it) }
    }
}