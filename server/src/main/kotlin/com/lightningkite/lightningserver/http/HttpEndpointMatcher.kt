package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath

class HttpEndpointMatcher(paths: Sequence<HttpEndpoint>) {
    data class Node(
        val path: Map<HttpMethod, HttpEndpoint>,
        val trailingSlash: Map<HttpMethod, HttpEndpoint>,
        val chainedWildcard: Map<HttpMethod, HttpEndpoint>,
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
            val future = paths.map { it.path }.filter {
                it.segments.asSequence().zip(soFar.asSequence())
                    .all { it.first.s() == it.second } && it.segments.size > soFar.size
            }
            return Node(
                path = paths.filter { it.path.segments.map { it.s() } == soFar && it.path.after == ServerPath.Afterwards.None }.associateBy { it.method },
                trailingSlash = paths.filter { it.path.segments.map { it.s() } == soFar && it.path.after == ServerPath.Afterwards.TrailingSlash }.associateBy { it.method },
                chainedWildcard = paths.filter { it.path.segments.map { it.s() } == soFar && it.path.after == ServerPath.Afterwards.ChainedWildcard }.associateBy { it.method },
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
        val endpoint: HttpEndpoint,
        val parts: Map<String, String>,
        val wildcard: String?
    )

    fun match(string: String, method: HttpMethod): Match? = match(string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'), method)
    fun match(pathParts: List<String>, endingSlash: Boolean, method: HttpMethod): Match? {
//        println("Navigating $pathParts with ending slash $endingSlash")
        val wildcards = ArrayList<String>()
        var current = root
        val soFar = arrayListOf<Node>()
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
            break
        }
//        println("Stopped at $current")
        return if (endingSlash) {
//            println("Pulling trailingSlash")
            current.trailingSlash.get(method)?.let {
                Match(
                    endpoint = it,
                    parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second },
                    wildcard = null
                )
            }
        } else {
//            println("Pulling path")
            current.path.get(method)?.let {
                Match(
                    endpoint = it,
                    parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second },
                    wildcard = null
                )
            }
        } ?: run {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard.get(method)?.let {
                    Match(
                        endpoint = it,
                        parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                            .associate { it.first.name to it.second },
                        wildcard = pathParts.drop(it.path.segments.size).joinToString("/") + (if (endingSlash) "/" else "")
                    )
                }
            }.firstOrNull()
        }
    }
}