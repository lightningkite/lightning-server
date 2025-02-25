package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath
import io.ktor.http.*

class HttpEndpointMatcher() {
    constructor(paths: Sequence<HttpEndpoint>):this() {
        paths.forEach { add(it) }
    }

    class Node {
        val path = HashMap<HttpMethod, HttpEndpoint>()
        val trailingSlash = HashMap<HttpMethod, HttpEndpoint>()
        val chainedWildcard = HashMap<HttpMethod, HttpEndpoint>()
        val thenConstant = HashMap<String, Node>()
        var thenWildcard: Node? = null
        fun thenWildcard(): Node {
            return thenWildcard ?: run {
                val n = Node()
                this.thenWildcard = n
                n
            }
        }
    }
    val root = Node()
    fun add(endpoint: HttpEndpoint) {
        var current = root
        for(segment in endpoint.path.segments) {
            current = when(segment) {
                is ServerPath.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is ServerPath.Segment.Wildcard -> current.thenWildcard()
            }
        }
        when(endpoint.path.after) {
            ServerPath.Afterwards.None -> current.path[endpoint.method] = endpoint
            ServerPath.Afterwards.TrailingSlash -> current.trailingSlash[endpoint.method] = endpoint
            ServerPath.Afterwards.ChainedWildcard -> current.chainedWildcard[endpoint.method] = endpoint
        }
    }

    data class Match(
        val endpoint: HttpEndpoint,
        val parts: Map<String, String>,
        val wildcard: String?
    )

    fun match(string: String, method: HttpMethod): Match? =
        match(string.split('/').filter { it.isNotEmpty() }, string.endsWith('/'), method)

    fun match(pathParts: List<String>, endingSlash: Boolean, method: HttpMethod): Match? {
        if (pathParts.isEmpty())
            return (root.path[method] ?: root.trailingSlash[method] ?: root.chainedWildcard[method])?.let {
                Match(
                    it,
                    mapOf(),
                    if (it.path.after == ServerPath.Afterwards.ChainedWildcard) "" else null
                )
            }
//        println("Navigating $pathParts with ending slash $endingSlash")
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
                it.chainedWildcard[method]?.let {
                    Match(
                        endpoint = it,
                        parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                            .associate { it.first.name to it.second.decodeURLPart() },
                        wildcard = pathParts.drop(it.path.segments.size)
                            .joinToString("/") + (if (endingSlash) "/" else "")
                    )
                }
            }.firstOrNull()
        } else if (endingSlash) {
//            println("Pulling trailingSlash")
            current.trailingSlash[method]?.let {
                Match(
                    endpoint = it,
                    parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second.decodeURLPart() },
                    wildcard = null
                )
            }
        } else {
//            println("Pulling path")
            current.path[method]?.let {
                Match(
                    endpoint = it,
                    parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                        .associate { it.first.name to it.second.decodeURLPart() },
                    wildcard = null
                )
            }
        } ?: run {
//            println("Searching for wildcard ending")
            soFar.asReversed().asSequence().mapNotNull {
                it.chainedWildcard[method]?.let {
                    Match(
                        endpoint = it,
                        parts = it.path.segments.filterIsInstance<ServerPath.Segment.Wildcard>().zip(wildcards)
                            .associate { it.first.name to it.second.decodeURLPart() },
                        wildcard = pathParts.drop(it.path.segments.size)
                            .joinToString("/") + (if (endingSlash) "/" else "")
                    )
                }
            }.firstOrNull()
        }
    }
}