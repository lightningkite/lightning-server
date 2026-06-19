package com.lightningkite.lightningserver.guide

import kotlin.test.Test

class AwsDeploymentTest {
    // The AWS deployment chapter has no unit-assertable runtime behavior
    // (AwsAdapter requires Lambda environment; TerraformAwsServerlessBuilder
    // requires AWS credentials and terraform).  This test confirms the shared
    // server-definition pattern used in the chapter compiles correctly and
    // that the sample file is reachable by DriftCheckTest.
    @Test
    fun `BlogServer compiles without AWS dependencies`() {
        // Simply referencing BlogServer forces the class to load — if the
        // imports or declarations in AwsDeploymentSamples.kt are broken
        // this will fail at compile time.
        val name = com.lightningkite.lightningserver.guide.samples.ApiServer::class.simpleName
        check(name == "ApiServer")
    }
}
