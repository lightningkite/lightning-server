package com.lightningkite.lightningserver

import org.junit.Assert.*
import org.junit.Test

class CasingUtilsKtTest {
    @Test fun testQuick() {
        val dumb = "As--d_f-_ XdcCase ID"
        assertEquals("As D F Xdc Case ID", dumb.titleCase())
        assertEquals("as-d-f-xdc-case-id", dumb.kabobCase())
        assertEquals("as_d_f_xdc_case_id", dumb.snakeCase())
        assertEquals("AS_D_F_XDC_CASE_ID", dumb.screamingSnakeCase())
        assertEquals("asDFXdcCaseID", dumb.camelCase())
        assertEquals("AsDFXdcCaseID", dumb.pascalCase())
    }
}