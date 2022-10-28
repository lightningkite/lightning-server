package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

interface FileSystem: HealthCheckable {
    val root: FileObject
    val rootUrls: List<String> get() = listOf(root.url)
    companion object {
        fun register(system: FileSystem) {
            system.rootUrls.forEach {
                roots.add(it to system)
            }
        }
        val urlRoots get() = roots.map { it.first }
        private val roots = ArrayList<Pair<String, FileSystem>>()
        fun resolve(url: String): FileObject? {
            val (root, sys) = roots.find { url.startsWith(it.first) } ?: return null
            return sys.root.resolve(url.removePrefix(root).substringBefore('?'))
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