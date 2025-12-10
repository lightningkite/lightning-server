@file:OptIn(ExperimentalLightningServer::class)

package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.services.data.ExperimentalLightningServer
import com.lightningkite.services.data.KFile
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.writeString
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An abstract representation of a writable archive that can contain files and nested directories.
 *
 * This interface abstracts over different storage mechanisms like filesystem directories,
 * ZIP archives, and single output streams. It provides a uniform API for writing hierarchical
 * file structures regardless of the underlying implementation.
 *
 * ## Usage Example
 * ```kotlin
 * Archive.zip(ZipOutputStream(outputStream)).use { archive ->
 *     archive.entry("readme.txt") {
 *         writeString("Hello World")
 *     }
 *     archive.sub("src").entry("Main.kt") {
 *         writeString("fun main() {}")
 *     }
 * }
 * ```
 *
 * ## Important Notes
 * - Writing to the same path multiple times may produce an error or undefined behavior
 * - The behavior depends on the specific implementation
 * - All write operations are performed immediately within the [entry] lambda
 * - Always close the archive when done to ensure proper resource cleanup
 *
 * @see KFileArchive for filesystem-based archives
 * @see ZipArchive for ZIP file archives
 * @see SingleStreamArchive for streaming to a single output sink
 */
@ExperimentalLightningServer("This interface is unstable and may change at any time.")
public interface Archive : AutoCloseable {
    /**
     * Creates a nested sub-archive representing a subdirectory.
     *
     * This method creates a logical subdirectory within the archive without
     * actually creating any entries. The subdirectory is created implicitly
     * when files are written to it via [entry].
     *
     * @param name The name of the subdirectory
     * @return A new [Archive] instance representing the subdirectory
     *
     * @sample
     * ```kotlin
     * archive.sub("src").sub("main").sink("App.kt") {
     *     writeString("package main")
     * }
     * // Creates: src/main/App.kt
     * ```
     */
    public fun sub(name: String): Archive

    /**
     * Creates and writes to an entry in this archive.
     *
     * An "entry" is an abstraction over a specific file or output stream. For example,
     * when using a [KFileArchive] `entry` creates and writes to a [KFile] with the
     * provided [name]. With [ZipArchive] an `entry` corresponds to a zip entry.
     *
     * This method creates a new file entry at the specified path and immediately
     * executes the provided [entry] lambda with a [Sink] as the receiver. All
     * writing must be completed within the lambda - the file entry is closed
     * when the lambda returns.
     *
     * **Important:** Writing to the same path multiple times is an error.
     * The specific error behavior depends on the underlying implementation:
     * - [ZipArchive] throws a [java.util.zip.ZipException]
     * - [KFileArchive] will overwrite the previous file
     * - [SingleStreamArchive] will append to the same stream
     *
     * ## Closing
     *
     * The [Sink] provided with a call to `entry` is closed when the lambda returns,
     * but is safe to close multiple times.
     *
     * @param name The name of the file to create
     * @param entry A lambda that writes content to the sink. The sink is automatically
     *              flushed and closed after the lambda completes.
     *
     * @sample
     * ```kotlin
     * archive.entry("config.json") {
     *     writeString("""{"port": 8080}""")
     * }
     * ```
     */
    public fun entry(name: String, write: Sink.() -> Unit)

    public companion object {
        /**
         * Creates a filesystem-based archive that writes files to a directory.
         *
         * Files are written directly to the filesystem as they are created.
         * Subdirectories are created automatically as needed.
         *
         * @param folder The root directory for the archive
         * @return A [KFileArchive] instance
         *
         * @sample
         * ```kotlin
         * val archive = Archive.folder(KFile("/path/to/output"))
         * archive.entry("file.txt") { writeString("content") }
         * // Writes to: /path/to/output/file.txt
         * ```
         */
        public fun folder(folder: KFile): KFileArchive = KFileArchive(folder)

        /**
         * Creates a ZIP archive that writes entries to a [ZipOutputStream].
         *
         * Files are written as ZIP entries in the order they are created.
         * The ZIP stream must be closed by calling [Archive.close] when done.
         *
         * @param zip The [ZipOutputStream] to write ZIP entries to
         * @return A [ZipArchive] instance
         *
         * @sample
         * ```kotlin
         * val zipOut = ZipOutputStream(FileOutputStream("output.zip"))
         * Archive.zip(zipOut).use { archive ->
         *     archive.entry("readme.txt") { writeString("Hello") }
         *     archive.sub("src").entry("Main.kt") { writeString("fun main()") }
         * }
         * ```
         */
        public fun zip(zip: ZipOutputStream): ZipArchive = ZipArchive(zip)
    }
}

/**
 * A filesystem-based implementation of [Archive] that writes files directly to a directory.
 *
 * This implementation creates actual files and directories on the filesystem as they are
 * written. Subdirectories are created automatically as needed.
 *
 * ## Behavior
 * - Files are written immediately to disk
 * - Writing to the same path multiple times will overwrite the previous file
 * - Parent directories are created automatically
 * - Closing this archive is a no-op (files are already persisted)
 *
 * @param folder The root directory where files will be written
 *
 * @see Archive.folder
 */
@ExperimentalLightningServer("This is unstable and may change at any time.")
public class KFileArchive(private val folder: KFile) : Archive {
    override fun sub(name: String): KFileArchive = KFileArchive(folder.then(name))

    override fun entry(name: String, write: Sink.() -> Unit) {
        folder.createDirectories()
        folder.then(name).sink().use(write)
    }

    override fun close() {}
}


private fun pathOf(base: String, new: String) = if (base.isEmpty()) new else "$base/$new"

/**
 * A single-stream implementation of [Archive] that writes all files to one continuous output stream.
 *
 * This implementation concatenates all file contents into a single sink, optionally inserting
 * delimiters between files. It's useful for:
 * - Streaming multiple files to a client without creating an actual archive
 * - Creating custom text-based archive formats
 * - Generating combined output files from multiple sources
 * - Situations where you know [entry] will only be called once but an [Archive] is expected
 *
 * ## Behavior
 * - All files are written sequentially to the same underlying sink
 * - Optional delimiters can be inserted before each file
 * - The underlying sink is never closed by individual file writes
 * - Calling [close] closes the underlying output sink
 *
 * ## Example with Delimiters
 * ```kotlin
 * SingleStreamArchive(outputSink) { path ->
 *     "\n// File: $path\n"
 * }.use { archive ->
 *     archive.entry("file1.kt") { writeString("fun hello()") }
 *     archive.entry("file2.kt") { writeString("fun world()") }
 * }
 * // Output:
 * // // File: file1.kt
 * // fun hello()
 * // // File: file2.kt
 * // fun world()
 * ```
 *
 * @param out The output sink where all content is written
 * @param basePath The current path prefix (empty for root, used internally for sub-archives)
 * @param delimiter Optional function that generates delimiter text based on the file path.
 *                  Called before each file is written. Returns null to skip delimiter.
 */
@ExperimentalLightningServer("This is unstable and may change at any time.")
public class SingleStreamArchive private constructor(
    private val out: Sink,
    private val basePath: String,
    public val delimiter: ((path: String) -> String)?
) : Archive {
    /**
     * Creates a root [SingleStreamArchive].
     *
     * @param out The output sink to write all content to
     * @param delimiter Optional function to generate delimiters before each file
     */
    public constructor(out: Sink, delimiter: ((path: String) -> String)? = null) : this(out, "", delimiter)

    override fun sub(name: String): SingleStreamArchive =
        SingleStreamArchive(out, pathOf(basePath, name), delimiter)

    override fun entry(name: String, write: Sink.() -> Unit) {
        delimiter?.invoke(pathOf(basePath, name))?.let(out::writeString)

        object : RawSink by out {
            override fun close() {}
        }
            .buffered()
            .use(write)
    }

    override fun close() { out.close() }
}

/**
 * A ZIP archive implementation of [Archive] that writes files as entries in a [ZipOutputStream].
 *
 * This implementation creates a standard ZIP archive with proper directory structure.
 * Files are written as sequential ZIP entries, and the underlying [ZipOutputStream]
 * is managed to ensure entries are properly opened and closed.
 *
 * ## Behavior
 * - Each file is written as a separate ZIP entry
 * - Subdirectories are represented in entry paths (e.g., "src/main/App.kt")
 * - Entries must be written sequentially (one at a time)
 * - The underlying ZIP stream is closed when [close] is called
 * - Writing to the same path multiple times creates duplicate entries (undefined behavior)
 *
 * ## Important Implementation Details
 * - The [ZipOutputStream] requires sequential entry writing
 * - Each [entry] call opens a new entry, writes content, then closes the entry
 * - The sink returned to the write lambda prevents premature closing of the underlying stream
 * - All data is automatically flushed when the write lambda completes
 *
 * ## Example
 * ```kotlin
 * val zipOut = ZipOutputStream(FileOutputStream("output.zip"))
 * Archive.zip(zipOut).use { archive ->
 *     // Root level file
 *     archive.entry("README.md") {
 *         writeString("# My Project")
 *     }
 *
 *     // Nested file
 *     archive.sub("src").sub("main").entry("App.kt") {
 *         writeString("fun main() { println(\"Hello\") }")
 *     }
 * }
 * // ZIP structure:
 * // README.md
 * // src/main/App.kt
 * ```
 *
 * @param zip The [ZipOutputStream] to write entries to
 * @param basePath The current path prefix (empty for root, used internally for sub-archives)
 *
 * @see Archive.zip
 */
@ExperimentalLightningServer("This is unstable and may change at any time.")
public class ZipArchive private constructor(
    private val zip: ZipOutputStream,
    private val basePath: String,
): Archive {
    /**
     * Creates a root [ZipArchive].
     *
     * @param zip The [ZipOutputStream] to write ZIP entries to
     */
    public constructor(zip: ZipOutputStream) : this(zip, "")

    override fun sub(name: String): ZipArchive =
        ZipArchive(zip, pathOf(basePath, name))

    override fun entry(name: String, write: Sink.() -> Unit) {
        zip.putNextEntry(ZipEntry(pathOf(basePath, name)))

        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) = zip.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
            override fun flush() = zip.flush()

            override fun close() {}
        }.asSink().buffered()

        sink.use(write)

        zip.closeEntry()
    }

    override fun close() { zip.close() }
}

