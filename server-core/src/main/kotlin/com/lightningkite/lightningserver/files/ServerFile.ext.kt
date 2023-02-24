package com.lightningkite.lightningserver.files

import com.lightningkite.lightningdb.ServerFile
import com.lightningkite.lightningserver.client
import java.io.File

val ServerFile.fileObject: FileObject get() = FileSystem.resolve(this.location)!!
