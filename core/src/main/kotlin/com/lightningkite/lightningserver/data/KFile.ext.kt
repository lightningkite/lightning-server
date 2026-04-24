package com.lightningkite.lightningserver.data

import com.lightningkite.services.kfile.KFile
import kotlinx.io.files.*
import java.io.File

/**
 * Converts a multiplatform [KFile] to a Java [File].
 *
 * This is useful when you need to interact with Java APIs that require [File] instances.
 *
 * Note: This is JVM-specific functionality and will not work on other platforms.
 *
 * @return A Java File pointing to the same path
 */
public fun KFile.toJavaFile(): File = File(path.toString())

/**
 * Converts a Java [File] to a multiplatform [KFile].
 *
 * This allows you to use Java File objects with Lightning Server's multiplatform file APIs.
 *
 * @param fileSystem The FileSystem to use for the KFile. Defaults to SystemFileSystem.
 * @return A KFile wrapping the same file path
 */
public fun File.toKFile(fileSystem: FileSystem = SystemFileSystem): KFile = KFile(fileSystem, Path(path))

/*
 * TODO: API Recommendations for KFile.ext.kt
 *
 * 1. Consider adding path validation or normalization to handle edge cases like:
 *    - Relative vs absolute paths
 *    - Path separators on different platforms
 *    - Symlinks and canonical paths
 *
 * 2. Add conversion functions for other common Java file types if needed:
 *    - java.nio.file.Path.toKFile()
 *    - KFile.toNioPath()
 */