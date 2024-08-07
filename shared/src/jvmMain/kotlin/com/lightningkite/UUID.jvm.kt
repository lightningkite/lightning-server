@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lightningkite

actual typealias UUIDRaw = java.util.UUID
actual fun uuid(): UUIDRaw = java.util.UUID.randomUUID()
actual fun uuid(string: String): UUIDRaw = java.util.UUID.fromString(string)