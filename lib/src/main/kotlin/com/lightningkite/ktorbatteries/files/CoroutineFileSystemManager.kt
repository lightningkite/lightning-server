package com.lightningkite.ktorbatteries.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.vfs2.FileObject
import org.apache.commons.vfs2.FileSystemManager

class CoroutineFileSystemManager(val manager: FileSystemManager) {

    suspend fun resolveFile(path: String): FileObject = withContext(Dispatchers.IO) {
        manager.resolveFile(path)
    }

}

val FileSystemManager.coroutine get() = CoroutineFileSystemManager(this)