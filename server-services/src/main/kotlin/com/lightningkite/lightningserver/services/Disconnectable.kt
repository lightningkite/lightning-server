package com.lightningkite.lightningserver.services

/**
 * Added calls for handling connecting and disconnecting to external services that require constant open connections
 * Ex: Database
 */
interface Disconnectable {
    suspend fun disconnect() {}
    suspend fun connect() {}
}