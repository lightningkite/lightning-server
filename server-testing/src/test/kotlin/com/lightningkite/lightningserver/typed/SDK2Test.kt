package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import org.junit.Test

class SDK2Test {
    @Test fun test() {
        TestSettings
        with(SDK2) {
            System.out.writeInterface("com.lightningkite.lightningdb.test")
            println("// -----------")
            println("// -----------")
            println("// -----------")
            System.out.writeLive("com.lightningkite.lightningdb.test")
            println("// -----------")
            println("// -----------")
            println("// -----------")
            System.out.writeCached("com.lightningkite.lightningdb.test")
        }
    }
}