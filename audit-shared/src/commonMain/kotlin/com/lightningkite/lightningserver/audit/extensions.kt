package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.TypedId
import kotlin.time.Instant

public fun <ID : TypedId<UuidV7, ID>> ID.timestamp(): Instant = raw.timestamp()