package com.lightningkite.lightningserver.files

interface FileSystem {
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
}