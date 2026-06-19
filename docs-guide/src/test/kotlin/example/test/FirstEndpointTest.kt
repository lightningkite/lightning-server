// This file was automatically generated from first-endpoint.md by Knit tool. Do not edit.
package com.lightningkite.lightningserver.guide.test

import org.junit.Test
import kotlinx.knit.test.*

class FirstEndpointTest {
    @Test
    fun testExampleFirstEndpoint01() {
        captureOutput("ExampleFirstEndpoint01") { com.lightningkite.lightningserver.guide.exampleFirstEndpoint01.main() }.also { lines ->
            check(lines.last() == "Hello, Lightning Server!")
        }
    }

    @Test
    fun testExampleFirstEndpoint02() {
        captureOutput("ExampleFirstEndpoint02") { com.lightningkite.lightningserver.guide.exampleFirstEndpoint02.main() }.verifyOutputLines(
            "Hello, World!"
        )
    }

    @Test
    fun testExampleFirstEndpoint03() {
        captureOutput("ExampleFirstEndpoint03") { com.lightningkite.lightningserver.guide.exampleFirstEndpoint03.main() }.verifyOutputLines(
            "true",
            "true"
        )
    }
}
