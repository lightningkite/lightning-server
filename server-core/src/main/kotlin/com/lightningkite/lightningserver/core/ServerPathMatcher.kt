package com.lightningkite.lightningserver.core

import com.lightningkite.lightningserver.http.HttpEndpoint
import kotlin.sequences.forEach

class ServerPathMatcher() {
    constructor(paths: Sequence<ServerPath>):this() {
        paths.forEach { add(it) }
    }
    class Node {
        var path: ServerPath? = null
        var trailingSlash: ServerPath? = null
        var chainedWildcard: ServerPath? = null
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
    fun add(path: ServerPath) {
        var current = root
        for(segment in path.segments) {
            current = when(segment) {
                is ServerPath.Segment.Constant -> current.thenConstant.getOrPut(segment.value) { Node() }
                is ServerPath.Segment.Wildcard -> current.thenWildcard()
            }
        }
        when(path.after) {
            ServerPath.Afterwards.None -> current.path = path
            ServerPath.Afterwards.TrailingSlash -> current.trailingSlash = path
            ServerPath.Afterwards.ChainedWildcard -> current.chainedWildcard = path
        }
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