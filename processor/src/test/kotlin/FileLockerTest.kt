package com.lightningkite.lightningdb

import kotlin.test.Test

class FileLockerTest {
    @Test fun test() {
        /*
        GOAL:
        Concurrent usages don't die.

        - Abandon if already up to date
        - Start by locking with hash
        - Build to a new folder
        - On complete, swap folders in
         */
    }
}