package com.lightningkite

import kotlin.jvm.JvmInline

@JvmInline
value class HeaderValue(val string: String) {
    // TODO: No escaping?  Maybe this should escape.
    constructor(
        root: String,
        parameters: List<Pair<String, String>>
    ) : this(root + parameters.joinToString("") { ";${it.first}=${it.second}" })

    val withoutParameters: HeaderValue get() = HeaderValue(string.substringBefore(';'))
    val root: String get() = string.substringBefore(';')
    override fun toString(): String = string
}