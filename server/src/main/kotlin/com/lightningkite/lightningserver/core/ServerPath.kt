package com.lightningkite.lightningserver.core

data class ServerPath(val segments: List<Segment>, val after: Afterwards = Afterwards.None) {
    companion object {
        val root = ServerPath(listOf())
    }

    enum class Afterwards {
        None,
        TrailingSlash,
        ChainedWildcard;

        companion object {
            fun fromString(string: String): Afterwards {
                if(string.endsWith("/{...}"))
                    return ChainedWildcard
                else if(string.endsWith("/"))
                    return TrailingSlash
                else return None
            }
        }
    }

    sealed class Segment {
        data class Wildcard(val name: String) : Segment() {
            override fun toString(): String = "{$name}"
        }

        data class Constant(val value: String) : Segment() {
            override fun toString(): String = value
        }

        companion object {
            fun fromString(string: String): List<Segment> {
                return string.split('/')
                    .filter { it.isNotBlank() }
                    .filter { it != "{...}" }
                    .map {
                        if (it.startsWith("{"))
                            Segment.Wildcard(it.removePrefix("{").removeSuffix("}"))
                        else
                            Segment.Constant(it)
                    }
            }
        }
    }

    val parent: ServerPath get() = ServerPath(segments.dropLast(1))

    constructor(string: String) : this(
        segments = Segment.fromString(string),
        after = Afterwards.fromString(string)
    )

    @LightningServerDsl
    fun path(string: String, configure: ServerPath.() -> Unit = {}) = ServerPath(
        segments = segments + Segment.fromString(string),
        after = Afterwards.fromString(string)
    )
        .apply(configure)

    override fun toString(): String = "/" + segments.joinToString("/") + when(after) {
        Afterwards.None -> ""
        Afterwards.TrailingSlash -> "/"
        Afterwards.ChainedWildcard -> "/{...}"
    }
}