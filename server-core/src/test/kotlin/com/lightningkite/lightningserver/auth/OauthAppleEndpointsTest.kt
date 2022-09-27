package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.TestSettings
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OauthAppleEndpointsTest {
    @Test
    fun generateJwtTest() {
        TestSettings
        val appleCreds = File("./local/apple.txt").takeIf { it.exists() }?.readLines() ?: return
        println(OauthAppleEndpoints.generateJwt(appleCreds[0], appleCreds[1]))
    }
}