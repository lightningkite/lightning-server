package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus

/**
 * An abstracted model for reading and writing files in a storage solution.
 * Every implementation will handle how to resolve FileObjects in their own system.
 */
interface FileSystem : HealthCheckable, Disconnectable {
    val root: FileObject
    val rootUrls: List<String> get() = listOf(root.url)

    companion object {
        lateinit var default: () -> FileSystem

        fun register(system: FileSystem) {
            system.rootUrls.forEach {
                roots.add(it to system)
            }
        }
        fun register(specialResolver: SpecialResolver) {
            specialResolvers += specialResolver
        }

        val urlRoots get() = roots.map { it.first } + specialResolvers.map { it.prefix }
        private val roots = ArrayList<Pair<String, FileSystem>>()
        private val specialResolvers = ArrayList<SpecialResolver>()
        fun resolve(url: String): FileObject? {
            specialResolvers.find { url.startsWith(it.prefix) }?.resolve(url)?.let { return it }
            val (root, sys) = roots.find { url.startsWith(it.first) } ?: return null
            return sys.root.resolve(url.removePrefix(root).substringBefore('?'))
        }
    }

    interface SpecialResolver {
        val prefix: String
        fun resolve(url: String): FileObject
    }

    override suspend fun healthCheck(): HealthStatus {
        try {
            val testFile = root.resolve("health-check/test-file.txt")
            val testContent = "Test Content"
            testFile.put(HttpContent.Text(testContent, ContentType.Text.Plain))
            if (testFile.head()?.type != ContentType.Text.Plain) return HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "Test write resulted in file of incorrect MIME type"
            )
            if (testFile.get()!!.text() != testContent) return HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "Test content did not match"
            )
            return HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            return HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}