package com.lightningkite.lightningserver.encryption

public interface Encryptor {
    public val name: String
    public fun encrypt(bytes: ByteArray): ByteArray
    public fun decrypt(bytes: ByteArray): ByteArray
    public fun encryptSize(size: Int): Int
    public fun decryptSize(size: Int): Int


}