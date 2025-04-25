package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.TestSettings
import org.junit.Test

class SDK2Test {
    @Test fun test() {
        TestSettings
        with(SDK2) {
            System.out.writeInterface("com.lightningkite.lightningserver.db.test")
            println("// -----------")
            println("// -----------")
            println("// -----------")
            System.out.writeLive("com.lightningkite.lightningserver.db.test")
            println("// -----------")
            println("// -----------")
            println("// -----------")
            System.out.writeCached("com.lightningkite.lightningserver.db.test")
        }
    }
}