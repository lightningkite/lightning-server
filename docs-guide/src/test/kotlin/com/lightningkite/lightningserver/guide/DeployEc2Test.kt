package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.deployec2.Production
import com.lightningkite.lightningserver.guide.samples.deployec2.ProductionFleet
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// write() only generates Terraform JSON; it needs no AWS credentials or network.
class DeployEc2Test {
    private fun File.resources(name: String): JsonObject =
        Json.parseToJsonElement(resolve("$name.tf.json").readText()).jsonObject["resource"]!!.jsonObject

    private fun File.settings(): JsonObject =
        Json.parseToJsonElement(resolve("deployment.tf.json").readText()).jsonObject["locals"]!!.jsonObject["settings_raw"]!!.jsonObject

    @Test
    fun singleInstanceWrites() {
        val root = Production.terraformRoot
        try {
            Production.write()
            assertEquals(File("terraform/blog-api"), root)
            assertTrue(root.resources("ec2").containsKey("aws_instance"))
            assertTrue(root.resources("eip").containsKey("aws_eip"))
            val ktor = root.settings()["ktorRunConfig"]!!.jsonObject
            assertEquals("127.0.0.1", ktor["host"]!!.jsonPrimitive.content)
            assertEquals(8080, ktor["port"]!!.jsonPrimitive.int)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    @Test
    fun scalingFleetWrites() {
        val root = ProductionFleet.terraformRoot
        try {
            ProductionFleet.write()
            assertTrue(root.resources("asg").containsKey("aws_autoscaling_group"))
            assertTrue(root.resources("alb").containsKey("aws_lb"))
            val settings = root.settings()
            assertEquals(settings["cache"], settings["engine-cache"])
            assertEquals("0.0.0.0", settings["ktorRunConfig"]!!.jsonObject["host"]!!.jsonPrimitive.content)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }
}
