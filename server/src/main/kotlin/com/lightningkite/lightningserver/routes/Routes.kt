package com.lightningkite.lightningserver.routes

import com.lightningkite.lightningserver.core.ServerPath

private val docNames = HashMap<ServerPath, String?>()
public var ServerPath.docName: String?
    get() = docNames[this]
    set(value) { docNames[this] = value }
