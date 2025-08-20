package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId

public class SessionManager<SUBJECT : HasId<ID>, ID : Comparable<ID>>(
    public val principal: PrincipalType<SUBJECT, ID>,
    public val database: Runtime<Database>,

) {
}