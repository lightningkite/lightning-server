package com.lightningkite.lightningserver.routes

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.settings.generalSettings

private val docNames = HashMap<ServerPath, String?>()
public var ServerPath.docName: String?
    get() = docNames[this]
    set(value) { docNames[this] = value }

fun ServerPath.fullUrl(parts: Map<String, String> = mapOf(), wildcard: String = ""): String = generalSettings().publicUrl + this.toString(parts = parts, wildcard = wildcard)