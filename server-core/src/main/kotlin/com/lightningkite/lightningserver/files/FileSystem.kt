package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

interface FileSystem: HealthCheckable {
    val root: FileObject
    companion object {
        fun register(system: FileSystem) {
            registered.add(system)
        }
        val urlRoots get() = registered.map { it.root.url }
        private val registered = ArrayList<FileSystem>()
        fun resolve(url: String): FileObject? {
            val sys = registered.find { url.startsWith(it.root.url) } ?: return null
            return sys.root.resolve(url.removePrefix(sys.root.url))
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        try {
            root.list()
            return HealthStatus(HealthStatus.Level.OK)
        } catch(e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}