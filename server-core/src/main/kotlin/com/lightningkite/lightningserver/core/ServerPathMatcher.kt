package com.lightningkite.lightningserver.core

class ServerPathMatcher(paths: Sequence<ServerPath>) {
    data class Node(
        val path: ServerPath?,
        val trailingSlash: ServerPath?,
        val chainedWildcard: ServerPath?,
        val thenConstant: Map<String, Node>,
        val thenWildcard: Node?,
    ) {
        override fun toString(): String {
            return """Node(
                path = $path, 
                trailingSlash = $trailingSlash, 
                chainedWildcard = $chainedWildcard, 
                thenConstant = ${thenConstant.keys.joinToString()}, 
                thenWildcard = ${thenWildcard != null}, 
            )""".trimIndent().replace("\n", "")
        }
    }

    val root = run {
        fun ServerPath.Segment.s(): String? = when (this) {
            is ServerPath.Segment.Constant -> value
            is ServerPath.Segment.Wildcard -> null
        }

        fun toNode(soFar: List<String?>): Node {
            val future = paths.filter {
                it.segments.asSequence().zip(soFar.asSequence())
                    .all { it.first.s() == it.second } && it.segments.size > soFar.size
            }
            return Node(
                path = paths.find { it.segments.map { it.s() } == soFar && it.after == ServerPath.Afterwards.None },
                trailingSlash = paths.find { it.segments.map { it.s() } == soFar && it.after == ServerPath.Afterwards.TrailingSlash },
                chainedWildcard = paths.find { it.segments.map { it.s() } == soFar && it.after == ServerPath.Afterwards.ChainedWildcard },
                thenConstant = future
                    .mapNotNull { (it.segments[soFar.size] as? ServerPath.Segment.Constant)?.value }
                    .distinct()
                    .associateWith { toNode(soFar + it) },
                thenWildcard = if (future.any { it.segments[soFar.size] is ServerPath.Segment.Wildcard })
                    toNode(soFar + null)
                else null
            )
        }
        toNode(listOf())
    }

    data class Match(
        val path: ServerPath,
        val parts: Map<String, String>,
        val wildcard: String?
    )

    fun match(string: String): Match? = match(string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'))
    fun match(pathParts: List<String>, endingSlash: Boolean): Match? {
        if (pathParts.isEmpty())
            return (root.path ?: root.trailingSlash ?: root.chainedWildcard)?.let {
                Match(ServerPath.root, mapOf(), if (it.after == ServerPath.Afterwards.ChainedWildcard) "" else null)
            }

//        println("Navigating $pathParts with ending slash $endingSlash")
        if (pathParts.isEmpty()) {
            return Match(ServerPath.root, mapOf(), null)
        }
        val wildcards = ArrayList<String>()
        var current = root
        val soFar = arrayListOf<Node>()
        var beyond = false
        for (part in pathParts) {
            soFar.add(current)
//            println("Current is $current, looking for $part")
            val c = current.thenConstant[part]
            if (c != null) {
                current = c
                continue
            }
            val w = current.thenWildcard
            if (w != null) {
                current = w
                wildcards.add(part)
                continue
            }
            beyond = true
            break
        }
//        println("Stopped at $current")
        return if (beyond) {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard?.let {
                    Match(
                        path = it,
                        parts = it.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                            .associate { it.first.name to it.second },
                        wildcard = pathParts.drop(it.segments.size)
                            .joinToString("/") + (if (endingSlash) "/" else "")
                    )
                }
            }.firstOrNull()
        } else if (endingSlash) {
//            println("Pulling trailingSlash")
            current.trailingSlash?.let {
                Match(
                    path = it,
                    parts = it.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second },
                    wildcard = null
                )
            }
        } else {
//            println("Pulling path")
            current.path?.let {
                Match(
                    path = it,
                    parts = it.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second },
                    wildcard = null
                )
            }
        } ?: run {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard?.let {
                    Match(
                        path = it,
                        parts = it.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                            .associate { it.first.name to it.second },
                        wildcard = pathParts.drop(it.segments.size).joinToString("/") + (if (endingSlash) "/" else "")
                    )
                }
            }.firstOrNull()
        }
    }
}