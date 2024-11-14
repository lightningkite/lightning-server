package com.lightningkite.lightningserver.scim

import com.lightningkite.lightningdb.test.LargeTestModel
import com.lightningkite.lightningdb.test.prepareModelsServerTesting
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import kotlin.test.Test

class ScimConditionSerializerTest {
    init {
        prepareModelsServerScim()
        prepareModelsServerTesting()
    }

    @Test
    fun test() {
        listOf(
            """ userName eq "bjensen" """,
            """ name.familyName co "O'Malley" """,
            """ userName sw "J" """,
            """ urn:ietf:params:scim:schemas:core:2.0:User:userName sw "J" """,
            """ title pr """,
            """ meta.lastModified gt "2011-05-13T04:42:34Z" """,
            """ meta.lastModified ge "2011-05-13T04:42:34Z" """,
            """ meta.lastModified lt "2011-05-13T04:42:34Z" """,
            """ meta.lastModified le "2011-05-13T04:42:34Z" """,
            """ title pr and userType eq "Employee" """,
            """ title pr or userType eq "Intern" """,
//            """ schemas eq "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User" """,
            """ userType eq "Employee" and (emails co "example.com" or emails.value co "example.org") """,
            """ userType ne "Employee" and not (emails co "example.com" or emails.value co "example.org") """,
            """ userType eq "Employee" and (emails.type eq "work") """,
            """ userType eq "Employee" and emails[type eq "work" and value co "@example.com"] """,
            """ emails[type eq "work" and value co "@example.com"] or ims[type eq "xmpp" and value co "@foo.com"] """,
        ).forEach {
            println(it)
            val c = ScimConditionSerializer(ScimUser.serializer()).fromString(it.trim())
            println("  " + c)
            // validity check
            println("  " + Serialization.json.encodeToString(c))
        }
    }
}