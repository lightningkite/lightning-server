package com.lightningkite

actual typealias UUIDRaw = java.util.UUID
actual fun uuid(): UUIDRaw = java.util.UUID.randomUUID()
actual fun uuid(string: String): UUIDRaw = java.util.UUID.fromString(string)