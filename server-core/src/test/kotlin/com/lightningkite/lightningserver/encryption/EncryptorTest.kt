package com.lightningkite.lightningserver.encryption

import com.lightningkite.lightningserver.encryption.*
import org.junit.Assert.*
import org.junit.Test

class EncryptorTest {
    @Test fun sign() {
        val encryptor = SecretBasis().encryptor("test")
        encryptor.decrypt(encryptor.encrypt(ByteArray(255) { it.toByte() }))
    }
}