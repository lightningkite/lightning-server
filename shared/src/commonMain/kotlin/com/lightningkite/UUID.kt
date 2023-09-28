package com.lightningkite

expect class UUID: Comparable<UUID>
expect fun uuid(): UUID
expect fun uuid(string: String): UUID
