package com.lightningkite

actual typealias UUID = java.util.UUID
actual fun uuid(): UUID = java.util.UUID.randomUUID()
actual fun uuid(string: String): UUID = java.util.UUID.fromString(string)