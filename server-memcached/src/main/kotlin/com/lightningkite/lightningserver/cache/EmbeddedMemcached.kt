package com.lightningkite.lightningserver.cache

import java.io.File

object EmbeddedMemcached {
    val available: Boolean by lazy {
        System.getenv("PATH").split(File.pathSeparatorChar)
            .any {
                File(it).listFiles()?.any {
                    it.name.startsWith("memcached")
                } ?: false
            }
    }
    fun start() = ProcessBuilder().command("memcached").start()
}