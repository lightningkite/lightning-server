package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.Server.database
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.jsonfile.JsonFileDatabase

/**
 * Shared test helper for all demo test classes.
 *
 * This ensures that the Server is only built once, preventing DuplicateRegistrationError
 * when running multiple test classes together.
 */
object TestHelper {
    init {
        JsonFileDatabase // Ensure service implementations are loaded
    }

   inline fun testServer(action: context(TestRunner<Server>) Server.()->Unit) {
        Server.test(settings = { database set Database.Settings("ram") }, action)
    }
}
