package com.lightningkite.lightningserver.terraform.awsserverless

import java.io.File
import kotlin.test.*

/**
 * Tests for the CloudFront Function JavaScript that transforms URI paths to query parameters.
 * These tests run the actual JavaScript code through Node.js to verify correctness.
 */
class CloudFrontWsPathTransformTest {

    /**
     * Runs the CloudFront Function handler with a simulated event and returns the result.
     * Uses Node.js to execute the JavaScript.
     */
    private fun runHandler(uri: String, querystring: Map<String, Any> = emptyMap()): Map<String, Any> {
        val querystringJson = buildString {
            append("{")
            querystring.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(",")
                append("\"$key\":")
                when (value) {
                    is String -> append("{\"value\":\"$value\"}")
                    is Map<*, *> -> {
                        // Handle multiValue case
                        @Suppress("UNCHECKED_CAST")
                        val mv = value as Map<String, Any>
                        if (mv.containsKey("multiValue")) {
                            @Suppress("UNCHECKED_CAST")
                            val values = mv["multiValue"] as List<String>
                            append("{\"multiValue\":[")
                            values.forEachIndexed { i, v ->
                                if (i > 0) append(",")
                                append("{\"value\":\"$v\"}")
                            }
                            append("]}")
                        } else {
                            append("{\"value\":\"${mv["value"]}\"}")
                        }
                    }

                    else -> append("{\"value\":\"$value\"}")
                }
            }
            append("}")
        }

        val testScript = """
            $CLOUDFRONT_WS_PATH_TRANSFORM_FUNCTION

            var event = {
                request: {
                    uri: "$uri",
                    querystring: $querystringJson
                }
            };

            var result = handler(event);
            console.log(JSON.stringify(result));
        """.trimIndent()

        // Write script to temp file and run with Node
        val tempFile = File.createTempFile("cf-test-", ".js")
        try {
            tempFile.writeText(testScript)

            val process = ProcessBuilder("node", tempFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw RuntimeException("Node.js execution failed (exit code $exitCode): $output")
            }

            // Parse JSON output manually (simple parser for test purposes)
            return parseSimpleJson(output)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Simple JSON parser for test results. Only handles the specific structure we expect.
     */
    private fun parseSimpleJson(json: String): Map<String, Any> {
        // Use Kotlin's built-in JSON would be better, but keeping dependencies minimal for tests
        // This is a very simple parser that handles our specific output format
        val result = mutableMapOf<String, Any>()

        // Extract uri
        val uriMatch = Regex("\"uri\"\\s*:\\s*\"([^\"]*)\"").find(json)
        if (uriMatch != null) {
            result["uri"] = uriMatch.groupValues[1]
        }

        // Extract querystring.path.value
        val pathMatch = Regex("\"path\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]*)\"").find(json)
        if (pathMatch != null) {
            result["path"] = pathMatch.groupValues[1]
        }

        return result
    }

    @Test
    fun `path is transformed to query parameter`() {
        val result = runHandler("/voice/call")

        assertEquals("/", result["uri"], "URI should be reset to root")
        assertEquals("/voice/call", result["path"], "Path should be preserved in query parameter")
    }

    @Test
    fun `root path is not modified`() {
        val result = runHandler("/")

        assertEquals("/", result["uri"], "URI should remain root")
        assertTrue(result["path"] == null || result["path"] == "", "No path query param should be added for root")
    }

    @Test
    fun `empty path is not modified`() {
        val result = runHandler("")

        // Empty string should not be modified
        assertTrue(result["path"] == null || result["path"] == "", "No path query param should be added for empty path")
    }

    @Test
    fun `nested path is preserved`() {
        val result = runHandler("/api/v1/voice/streams/123")

        assertEquals("/", result["uri"])
        assertEquals("/api/v1/voice/streams/123", result["path"])
    }

    @Test
    fun `existing query params are included in path value`() {
        val result = runHandler(
            uri = "/voice/call",
            querystring = mapOf("token" to "abc123")
        )

        assertEquals("/", result["uri"])
        // The path value should include the original query params
        assertTrue(
            result["path"]?.toString()?.contains("token=abc123") == true,
            "Path should contain existing query params: ${result["path"]}"
        )
    }

    @Test
    fun `multiple query params are preserved`() {
        val result = runHandler(
            uri = "/voice/call",
            querystring = mapOf(
                "token" to "abc",
                "user" to "test"
            )
        )

        assertEquals("/", result["uri"])
        val path = result["path"]?.toString() ?: ""
        assertTrue(path.contains("token=abc"), "Should contain token param: $path")
        assertTrue(path.contains("user=test"), "Should contain user param: $path")
    }

    @Test
    fun `special characters in path are preserved`() {
        val result = runHandler("/voice/call-stream")

        assertEquals("/", result["uri"])
        assertEquals("/voice/call-stream", result["path"])
    }

    @Test
    fun `multiValue query params are handled`() {
        val result = runHandler(
            uri = "/test",
            querystring = mapOf(
                "tags" to mapOf("multiValue" to listOf("a", "b", "c"))
            )
        )

        assertEquals("/", result["uri"])
        val path = result["path"]?.toString() ?: ""
        assertTrue(path.contains("tags=a"), "Should contain first tag: $path")
        assertTrue(path.contains("tags=b"), "Should contain second tag: $path")
        assertTrue(path.contains("tags=c"), "Should contain third tag: $path")
    }
}
