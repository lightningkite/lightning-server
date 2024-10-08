package com.lightningkite.lightningserver.files

val ServerFile.fileObject: FileObject get() = FileSystem.resolve(this.location) ?: throw IllegalArgumentException("Matching system not found for $location; accepted systems are ${FileSystem.urlRoots}")
