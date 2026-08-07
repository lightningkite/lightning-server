package com.lightningkite.lightningserver.guide

import org.junit.Test
import java.io.File
import kotlin.test.fail

// Verifies that every fenced code block in the guide Markdown files is
// byte-identical to the named region in the corresponding sample source.
//
// HOW IT WORKS
//
// 1. Sample sources in src/samples/kotlin/ are annotated with named regions:
//      // region hello-server
//      object HelloServer : ServerBuilder() { ... }
//      // endregion hello-server
//
// 2. Each guide Markdown file references these regions immediately before a
//    fenced code block using an HTML comment marker:
//      [sample: com/.../File.kt#region-tag]
//      ```kotlin
//      object HelloServer : ServerBuilder() { ... }
//      ```
//
// 3. This test finds every such marker, extracts the named region from the
//    sample source, and asserts it is byte-identical to the fenced block.
//
// WHAT FAILS CI
// - Editing sample source without updating Markdown: mismatch detected
// - Editing Markdown without updating sample source: mismatch detected
// - Removing a region tag while leaving the Markdown reference: error
class DriftCheckTest {

    // Gradle runs tests with the project dir as the working directory.
    private val moduleRoot = File(System.getProperty("user.dir"))
    private val guideDir = moduleRoot.resolve("guide")
    private val samplesRoot = moduleRoot.resolve("src/samples/kotlin")

    @Test
    fun `guide code blocks match sample source regions`() {
        val mdFiles = guideDir.walkTopDown()
            .filter { it.extension == "md" }
            .toList()

        check(mdFiles.isNotEmpty()) { "No Markdown files found in $guideDir" }

        val failures = mutableListOf<String>()

        for (mdFile in mdFiles.sorted()) {
            val lines = mdFile.readLines()
            var i = 0
            while (i < lines.size) {
                val markerMatch = SAMPLE_MARKER.matchEntire(lines[i])
                if (markerMatch != null) {
                    val relativePath = markerMatch.groupValues[1]
                    val regionTag = markerMatch.groupValues[2]
                    val sampleFile = samplesRoot.resolve(relativePath)

                    // Skip to the opening fence
                    i++
                    if (i >= lines.size || !lines[i].startsWith("```")) {
                        failures += "${mdFile.name}:${i + 1}: sample marker not followed by a fenced code block"
                        continue
                    }
                    i++ // skip the opening ``` line

                    // Collect fence body up to the closing ```
                    val blockLines = mutableListOf<String>()
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        blockLines += lines[i]
                        i++
                    }
                    i++ // skip closing ```

                    val blockContent = blockLines.joinToString("\n").trimEnd()

                    if (!sampleFile.exists()) {
                        failures += "${mdFile.name}: sample file not found: $sampleFile"
                        continue
                    }

                    val regionContent = extractRegion(sampleFile, regionTag)
                    if (regionContent == null) {
                        failures += "${mdFile.name}: region '$regionTag' not found in $relativePath"
                        continue
                    }

                    if (regionContent != blockContent) {
                        failures += buildString {
                            appendLine("${mdFile.name}: code block does not match sample region '$regionTag'")
                            appendLine("--- expected (sample source) ---")
                            appendLine(regionContent)
                            appendLine("--- actual (markdown) ---")
                            appendLine(blockContent)
                            append("--- end ---")
                        }
                    }
                } else {
                    i++
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Guide drift detected:\n\n" + failures.joinToString("\n\n"))
        }
    }

    // Extract the body between [// region tag] and [// endregion] from a source file.
    private fun extractRegion(file: File, tag: String): String? {
        val lines = file.readLines()
        val startMarker = "// region $tag"
        val endMarker = "// endregion"

        val startIdx = lines.indexOfFirst { it.trim() == startMarker }
        if (startIdx < 0) return null

        var endIdx = -1
        for (j in (startIdx + 1) until lines.size) {
            if (lines[j].trim().startsWith(endMarker)) {
                endIdx = j
                break
            }
        }
        if (endIdx < 0) return null

        return lines.subList(startIdx + 1, endIdx).joinToString("\n").trimEnd()
    }

    companion object {
        // Matches the sample-link HTML comment used in guide Markdown files.
        // Pattern: <!-- sample: path/to/File.kt#region-tag -->
        // Constructed from parts so the source file doesn't itself contain
        // a raw XML comment sequence that could confuse downstream processors.
        private val SAMPLE_MARKER: Regex = buildSamplePattern()

        private fun buildSamplePattern(): Regex {
            val open = "<!--"   // <!--
            val close = "-->"   // -->
            return Regex("$open sample: (.+?)#(.+?) $close")
        }
    }
}
