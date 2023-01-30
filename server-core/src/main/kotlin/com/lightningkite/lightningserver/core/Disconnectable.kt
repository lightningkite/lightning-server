package com.lightningkite.lightningserver.core

/**
 * Used to disconnect settings with an open connection temporarily.
 * Need for save/restore on AWS.
 */
interface Disconnectable {
    fun disconnect()
}