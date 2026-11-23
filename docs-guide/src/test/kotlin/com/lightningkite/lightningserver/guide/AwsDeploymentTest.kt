package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.awsdeployment.Production
import com.lightningkite.lightningserver.guide.samples.awsdeployment.Staging
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// write() only generates Terraform JSON; it needs no AWS credentials or network.
class AwsDeploymentTest {
    private fun File.tfJson(name: String): JsonObject = Json.parseToJsonElement(resolve("$name.tf.json").readText()).jsonObject

    @Test
    fun productionWritesLambdaTerraform() {
        val root = Production.terraformRoot
        try {
            Production.write()
            assertEquals(File("terraform/my-api"), root)
            val lambda = root.tfJson("lambda")["resource"]!!.jsonObject
            val function = lambda["aws_lambda_function"]!!.jsonObject["main"]!!.jsonObject
            assertEquals("com.lightningkite.lightningserver.guide.samples.awsdeployment.AwsHandler", function["handler"]!!.jsonPrimitive.content)
            assertEquals(30, function["timeout"]!!.jsonPrimitive.int)
            assertTrue(root.tfJson("database")["resource"]!!.jsonObject.containsKey("mongodbatlas_advanced_cluster"))
            assertTrue(root.tfJson("files")["resource"]!!.jsonObject.containsKey("aws_s3_bucket"))
            assertTrue(root.tfJson("http")["resource"]!!.jsonObject.containsKey("aws_acm_certificate"))
            val settings = root.tfJson("lambda")["locals"]!!.jsonObject["settings_raw"]!!.jsonObject
            assertEquals("dynamodb://us-west-2/my_api", settings["cache"]!!.jsonPrimitive.content)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    @Test
    fun stagingTuningIsApplied() {
        val root = Staging.terraformRoot
        try {
            Staging.write()
            val function = root.tfJson("lambda")["resource"]!!.jsonObject["aws_lambda_function"]!!.jsonObject["main"]!!.jsonObject
            assertEquals(60, function["timeout"]!!.jsonPrimitive.int)
            assertEquals(2048, function["memory_size"]!!.jsonPrimitive.int)
            assertTrue(function["layers"]!!.jsonArray.isNotEmpty(), "otelXRay adds the collector layer")
        } finally {
            File("server").deleteRecursively()
        }
    }
}
