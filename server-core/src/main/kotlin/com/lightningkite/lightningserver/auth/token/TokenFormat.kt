package com.lightningkite.lightningserver.auth.token

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.RequestAuth

interface TokenFormat {
    val type: String get() = "Bearer"
    fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> create(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        auth: RequestAuth<SUBJECT>
    ): String
    fun <SUBJECT : HasId<ID>, ID : Comparable<ID>> read(
        handler: Authentication.SubjectHandler<SUBJECT, ID>,
        value: String
    ): RequestAuth<SUBJECT>?
}

