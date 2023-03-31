package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.time.Duration

@Deprecated("Renamed to just 'cache'", ReplaceWith("Cache", "com.lightningkite.lightningserver.cache.Cache"))
typealias CacheInterface = Cache

/**
 * An abstracted model for communicating with a Cache.
 * Every implementation will handle how to get and set values in the underlying cache system.
 */
interface Cache : HealthCheckable {

    /**
     * Returns a value of type T from the cache.
     *
     * @param key The key that will be used to retrieve the value
     * @param serializer The serializer that will be used to turn the raw serialized data from the cache into T.
     * @return An instance of T, or null if the key did not exist in the cache.
     */
    suspend fun <T> get(key: String, serializer: KSerializer<T>): T?

    /**
     * Sets the instance of T provided into the cache under the key provided. If the key already exists the existing data will be overwritten.
     * You can optionally provide an expiration time on the key. After that duration the key will automatically be removed.
     *
     * @param key The key that will be used when placing the value into the database.
     * @param value The instance of T that you wish to store into the cache.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)

    /**
     * Sets the instance of T provided into the cache under the key provided. If the key already exists then the incoming value will not be added to the cache.
     * You can optionally provide an expiration time on the key. After that duration the key will automatically be removed.
     *
     * @param key The key that will be used when placing the value into the database.
     * @param value The instance of T that you wish to store into the cache.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration? = null
    ): Boolean

    /**
     * Will modify an existing value in the cache. If the key does not exist and the modifcation still returns a value
     * then the new value will be inserted into the cache.
     *
     * @param key The key that will be used when modifying the value into the database.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param maxTries How many times it will attempt to make the modification to the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     * @param modification A lambda that takes in a nullable T and returns a nullable T. If a non null value is returned it will be set in the cache using the key. If a null value is returned the key will be removed from the cache.
     */
    suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if (current == get(key, serializer)) {
                if (new != null)
                    set(key, new, serializer, timeToLive)
                else
                    remove(key)
                return true
            }
        }
        return false
    }


    /**
     * Updates the value under key by adding value to the numerical value stored in the cache.
     *
     * @param key The key that will be used when updating the value into the database.
     * @param value The Int you wish to add to the value already in the cache.
     * @param timeToLive (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    suspend fun add(key: String, value: Int, timeToLive: Duration? = null)


    /**
     * WARNING: A Dangerous call if you aren't sure.
     * Will remove EVERY key in the cache leaving you with an entirely empty cache.
     */
    suspend fun clear()

    /**
     * Removes a single key from cache. If the key didn't exist, nothing will happen.
     *
     * @param key The key that will be removed from the cache.
     */
    suspend fun remove(key: String)

    /**
     * Will attempt inserting data into the cache to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            set("health-check-test-key", true)
            if (get<Boolean>("health-check-test-key") == true) {
                HealthStatus(HealthStatus.Level.OK)
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

/**
 * A Helper function for the underlying get call.
 * This can make get calls much cleaner and less wordy when the types can be inferred.
 */
suspend inline fun <reified T : Any> Cache.get(key: String): T? {
    return get(key, Serialization.Internal.json.serializersModule.serializer<T>())
}

/**
 * A Helper function for the underlying set call.
 * This can make set calls much cleaner and less wordy when the types can be inferred.
 */
suspend inline fun <reified T : Any> Cache.set(key: String, value: T, timeToLive: Duration? = null) {
    return set(key, value, Serialization.Internal.json.serializersModule.serializer<T>(), timeToLive)
}

/**
 * A Helper function for the underlying set setIfNotExists call.
 * This can make setIfNotExists calls much cleaner and less wordy when the types can be inferred.
 */
suspend inline fun <reified T : Any> Cache.setIfNotExists(
    key: String,
    value: T,
    timeToLive: Duration? = null
): Boolean {
    return setIfNotExists(key, value, Serialization.Internal.json.serializersModule.serializer<T>(), timeToLive)
}


/**
 * A Helper function for the underlying set modify call.
 * This can make modify calls much cleaner and less wordy when the types can be inferred.
 */
suspend inline fun <reified T : Any> Cache.modify(
    key: String,
    maxTries: Int = 1,
    timeToLive: Duration? = null,
    noinline modification: (T?) -> T?
): Boolean =
    modify(key, Serialization.Internal.json.serializersModule.serializer<T>(), maxTries, timeToLive, modification)
