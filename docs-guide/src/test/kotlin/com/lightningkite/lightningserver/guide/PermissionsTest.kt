package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.permissionsExtrasTest
import com.lightningkite.lightningserver.guide.samples.permissionsRestrictionsTest
import com.lightningkite.lightningserver.guide.samples.permissionsTest
import kotlin.test.Test

class PermissionsTest {
    @Test fun `rows masks and update restrictions are enforced`() { permissionsTest() }
    @Test fun `anonymous create refused, custom endpoint and system access`() { permissionsExtrasTest() }
    @Test fun `restriction builders produce the documented conditions`() { permissionsRestrictionsTest() }
}
