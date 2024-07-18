package com.lightningkite

import kotlinx.serialization.Contextual

expect class UUIDRaw: Comparable<UUIDRaw> {
    override fun compareTo(other: UUIDRaw): Int
}
typealias UUID = @Contextual UUIDRaw
expect fun uuid(): UUIDRaw
expect fun uuid(string: String): UUIDRaw
