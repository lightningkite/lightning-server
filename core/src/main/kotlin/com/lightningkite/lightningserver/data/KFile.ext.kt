package com.lightningkite.lightningserver.data

import com.lightningkite.services.data.KFile
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import java.io.File

public fun KFile.toJavaFile(): File = File(path.toString())

public fun File.toKFile(fileSystem: FileSystem = SystemFileSystem): KFile = KFile(fileSystem, Path(path))