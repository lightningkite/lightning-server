package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.data.KFile
import kotlin.test.Test

class FetcherSdkTests {
    private val folder = KFile("./src/test/kotlin/com/lightningkite/lightningserver/typed/sdk/generated")

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
                traverseChildrenRecursively()
            }
        }.let(::println)
    }

    private fun KFile.overwrite(action: Appendable.() -> Unit) {
        parent?.createDirectories()
        sink().useAsAppendable(action)
    }

    @Test
    fun testInterface() {
        folder.then("Api.kt").overwrite { FetcherSdk.run { writeInterface(Server.modularBuild().sdk(), "com.lightningkite.lightningserver.typed.sdk") } }
    }
}