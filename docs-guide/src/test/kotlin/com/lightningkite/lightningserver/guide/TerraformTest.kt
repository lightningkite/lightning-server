package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.terraform.Production
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerraformTest {
    private fun File.tfJson(name: String): JsonObject = Json.parseToJsonElement(resolve("$name.tf.json").readText()).jsonObject

    @Test
    fun customFunctionVariableAndOverridesAreWritten() {
        val root = Production.terraformRoot
        try {
            Production.write()

            val queue = root.tfJson("jobQueueUrl")["resource"]!!.jsonObject["aws_sqs_queue"]!!.jsonObject["jobQueueUrl"]!!.jsonObject
            assertEquals("shop-api-jobQueueUrl", queue["name"]!!.jsonPrimitive.content)

            val lambda = root.tfJson("lambda")
            val settings = lambda["locals"]!!.jsonObject["settings_raw"]!!.jsonObject
            assertEquals("\${aws_sqs_queue.jobQueueUrl.url}", settings["jobQueueUrl"]!!.jsonPrimitive.content)
            assertEquals("\${var.stripeSecretKey}", settings["stripeSecretKey"]!!.jsonPrimitive.content)
            assertEquals("support@example.com", settings["supportEmail"]!!.jsonPrimitive.content)

            assertTrue(root.tfJson("variables")["variable"]!!.jsonObject.containsKey("stripeSecretKey"))

            val function = lambda["resource"]!!.jsonObject["aws_lambda_function"]!!.jsonObject["main"]!!.jsonObject
            assertEquals(50, function["reserved_concurrent_executions"]!!.jsonPrimitive.int)
            val env = function["environment"]!!.jsonObject["variables"]!!.jsonObject
            assertTrue(env.containsKey("JAVA_TOOL_OPTIONS"))

            val policy = lambda["locals"]!!.jsonObject["servicesAccessPolicy"]!!.jsonObject["Statement"]!!.jsonArray
            val actions = policy.flatMap { it.jsonObject["Action"]!!.jsonArray.map { a -> a.jsonPrimitive.content } }
            assertTrue("sqs:SendMessage" in actions)
            assertTrue("ses:SendEmail" in actions)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }
}
