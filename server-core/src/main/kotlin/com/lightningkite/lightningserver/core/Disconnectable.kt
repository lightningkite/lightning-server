package com.lightningkite.lightningserver.core

/**
 * Added calls for handling connecting and disconnecting to external services that require constant open connections
 * Ex: Database
 */
interface Disconnectable {
    suspend fun disconnect()
    suspend fun connect()
}