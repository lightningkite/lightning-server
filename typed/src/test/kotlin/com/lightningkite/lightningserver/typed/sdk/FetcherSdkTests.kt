package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.data.KFile
import kotlin.test.Test

class FetcherSdkTests {
    private val testPath = "C:\\Users\\huntd\\Lightning\\Projects\\lightning-server\\typed\\src\\test\\kotlin\\com\\lightningkite\\lightningserver\\typed\\sdk"

    private fun Appendable.appendDepth(depth: Int, value: CharSequence) {
        repeat(depth) { append('\t') }
        append(value)
        append('\n')
    }

    @Test
    fun dataStructure() {
        val sdk = Server.modularBuild().sdk()

        println("As Sequence")

        buildString {
            sdk.asSequence().forEach { (depth, path, module) -> appendLine("$path : ${module.info.interfaceName}") }
        }.let(::println)

        println()
        println("Traversing")

        buildString {
            sdk.traverse {
                appendDepth(it.depth, it.module.toString())
                traverseChildren()
            }
        }.let(::println)
    }

    @Test
    fun writeInterface() {
        FetcherSdk.write(Server.modularBuild().sdk(), KFile("$testPath\\fetcher\\basic"), "com.lightningkite.lightningserver.typed.sdk")
    }
}