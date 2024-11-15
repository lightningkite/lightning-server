package com.lightningkite.lightningserver.scim

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.toEmailAddress
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ScimSerializationTest {
    @Test
    fun test() {
        println(
            Serialization.json.encodeToString(ScimUser.serializer(), fullSampleUser)
        )
        println(
            Serialization.json.encodeToString(ScimHackerySerializer("SCIMROOT", ScimUser.serializer()), fullSampleUser)
        )
        assertEquals(
            Serialization.json.parseToJsonElement(fullSampleUserjson),
            Serialization.json.encodeToJsonElement(ScimHackerySerializer("SCIMROOT", ScimUser.serializer()), fullSampleUser)
        )
        assertEquals(
            Serialization.json.parseToJsonElement("[$fullSampleUserjson]"),
            Serialization.json.encodeToJsonElement(ScimHackerySerializer("SCIMROOT", ListSerializer(ScimUser.serializer())), listOf(fullSampleUser))
        )
        assertEquals(
            Serialization.json.parseToJsonElement("[$fullSampleUserjson]"),
            Serialization.json.encodeToJsonElement(ScimHackerySerializer("SCIMROOT", ListSerializer(ScimUser.serializer())), listOf(fullSampleUser))
        )
        Serialization.json.encodeToJsonElement(ScimHackerySerializer("SCIMROOT", ScimListResponse.serializer(ScimUser.serializer())),
            ScimListResponse(20, listOf(fullSampleUser, fullSampleUser), 1, 20)).let(::println)
    }
    @Test
    fun isolate() {
        Serialization.json.encodeToJsonElement(ScimHackerySerializer("SCIMROOT", ScimListResponse.serializer(ScimUser.serializer())),
            ScimListResponse(20, listOf(fullSampleUser, fullSampleUser), 1, 20)).let(::println)
    }

    val fullSampleUser = ScimUser(
        _id = "2819c223-7f76-453a-919d-413861904646",
        createdAt = Instant.parse("2010-01-23T04:56:22Z"),
        modifiedAt = Instant.parse("2011-05-13T04:42:34Z"),
        externalId = "701984",
        userName = "bjensen@example.com",
        name = ScimUserName(
            formatted = "Ms. Barbara J Jensen, III",
            familyName = "Jensen",
            givenName = "Barbara",
            middleName = "Jane",
            honorificPrefix = "Ms.",
            honorificSuffix = "III"
        ),
        displayName = "Babs Jensen",
        nickName = "Babs",
        profileUrl = "https://login.example.com/bjensen",
        emails = setOf(
            ScimEmailAddress(
                value = "bjensen@example.com".toEmailAddress(),
                type = "work",
                primary = true
            ),
            ScimEmailAddress(
                value = "babs@jensen.org".toEmailAddress(),
                type = "home"
            )
        ),
        addresses = setOf(
            ScimAddress(
                streetAddress = "100 Universal City Plaza",
                locality = "Hollywood",
                region = "CA",
                postalCode = "91608",
                country = "USA",
                formatted = "100 Universal City Plaza\nHollywood, CA 91608 USA",
                type = "work",
                primary = true
            ),
            ScimAddress(
                streetAddress = "456 Hollywood Blvd",
                locality = "Hollywood",
                region = "CA",
                postalCode = "91608",
                country = "USA",
                formatted = "456 Hollywood Blvd\nHollywood, CA 91608 USA",
                type = "home"
            )
        ),
        phoneNumbers = setOf(
            ScimPhoneNumber(
                value = "555-555-5555",
                type = "work"
            ),
            ScimPhoneNumber(
                value = "555-555-4444",
                type = "mobile"
            )
        ),
        ims = setOf(
            ScimIms(
                value = "someaimhandle",
                type = "aim"
            )
        ),
        photos = setOf(
            ScimPhoto(
                value =
                    "https://photos.example.com/profilephoto/72930000000Ccne/F",
                type = "photo"
            ),
            ScimPhoto(
                value =
                    "https://photos.example.com/profilephoto/72930000000Ccne/T",
                type = "thumbnail"
            )
        ),
        userType = "Employee",
        title = "Tour Guide",
        preferredLanguage = "en-US",
        locale = "en-US",
        timezone = TimeZone.of("America/Los_Angeles"),
        active = true,
        password = "t1meMasheen",
        groups = setOf(
            ScimUserOrGroupReference(
                value = "e9e30dba-f08f-4109-8486-d5c6a331660a",
                ref = "../Groups/e9e30dba-f08f-4109-8486-d5c6a331660a",
                display = "Tour Guides"
            ),
            ScimUserOrGroupReference(
                value = "fc348aa8-3835-40eb-a20b-c726e15c55b5",
                ref = "../Groups/fc348aa8-3835-40eb-a20b-c726e15c55b5",
                display = "Employees"
            ),
            ScimUserOrGroupReference(
                value = "71ddacd2-a8e7-49b8-a5db-ae50d0a5bfd7",
                ref = "../Groups/71ddacd2-a8e7-49b8-a5db-ae50d0a5bfd7",
                display = "US Employees"
            )
        ),
        x509Certificates = setOf(
            ScimCertificate(
                value =
                    """MIIDQzCCAqygAwIBAgICEAAwDQYJKoZIhvcNAQEFBQAwTjELMAkGA1UEBhMCVVMx
                EzARBgNVBAgMCkNhbGlmb3JuaWExFDASBgNVBAoMC2V4YW1wbGUuY29tMRQwEgYD
                VQQDDAtleGFtcGxlLmNvbTAeFw0xMTEwMjIwNjI0MzFaFw0xMjEwMDQwNjI0MzFa
                MH8xCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRQwEgYDVQQKDAtl
                eGFtcGxlLmNvbTEhMB8GA1UEAwwYTXMuIEJhcmJhcmEgSiBKZW5zZW4gSUlJMSIw
                IAYJKoZIhvcNAQkBFhNiamVuc2VuQGV4YW1wbGUuY29tMIIBIjANBgkqhkiG9w0B
                AQEFAAOCAQ8AMIIBCgKCAQEA7Kr+Dcds/JQ5GwejJFcBIP682X3xpjis56AK02bc
                1FLgzdLI8auoR+cC9/Vrh5t66HkQIOdA4unHh0AaZ4xL5PhVbXIPMB5vAPKpzz5i
                PSi8xO8SL7I7SDhcBVJhqVqr3HgllEG6UClDdHO7nkLuwXq8HcISKkbT5WFTVfFZ
                zidPl8HZ7DhXkZIRtJwBweq4bvm3hM1Os7UQH05ZS6cVDgweKNwdLLrT51ikSQG3
                DYrl+ft781UQRIqxgwqCfXEuDiinPh0kkvIi5jivVu1Z9QiwlYEdRbLJ4zJQBmDr
                SGTMYn4lRc2HgHO4DqB/bnMVorHB0CC6AV1QoFK4GPe1LwIDAQABo3sweTAJBgNV
                HRMEAjAAMCwGCWCGSAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZp
                Y2F0ZTAdBgNVHQ4EFgQU8pD0U0vsZIsaA16lL8En8bx0F/gwHwYDVR0jBBgwFoAU
                dGeKitcaF7gnzsNwDx708kqaVt0wDQYJKoZIhvcNAQEFBQADgYEAA81SsFnOdYJt
                Ng5Tcq+/ByEDrBgnusx0jloUhByPMEVkoMZ3J7j1ZgI8rAbOkNngX8+pKfTiDz1R
                C4+dx8oU6Za+4NJXUjlL5CvV6BEYb1+QAEJwitTVvxB/A67g42/vzgAtoRUeDov1
                +GFiBZ+GNF/cAYKcMtGcrs2i97ZkJMo="""
            )
        ),
        enterprise = ScimUserEnterprise(
            employeeNumber = "701984",
            costCenter = "4130",
            organization = "Universal Studios",
            division = "Theme Park",
            department = "Tour Operations",
            manager = ScimUserOrGroupReference(
                value = "26118915-6090-4610-87e4-49d8ca9f808d",
                ref = "../Users/26118915-6090-4610-87e4-49d8ca9f808d",
                display = "John Smith"
            )
        )
    )
    val fullSampleUserjson = """
{
  "schemas": [
    "urn:ietf:params:scim:schemas:core:2.0:User",
    "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"
  ],
  "meta": {
    "resourceType": "User",
    "created": "2010-01-23T04:56:22Z",
    "lastModified": "2011-05-13T04:42:34Z",
    "location": "SCIMROOT/User/2819c223-7f76-453a-919d-413861904646"
  },
  "id": "2819c223-7f76-453a-919d-413861904646",
  "externalId": "701984",
  "userName": "bjensen@example.com",
  "name": {
    "formatted": "Ms. Barbara J Jensen, III",
    "familyName": "Jensen",
    "givenName": "Barbara",
    "middleName": "Jane",
    "honorificPrefix": "Ms.",
    "honorificSuffix": "III"
  },
  "displayName": "Babs Jensen",
  "nickName": "Babs",
  "profileUrl": "https://login.example.com/bjensen",
  "title": "Tour Guide",
  "userType": "Employee",
  "preferredLanguage": "en-US",
  "locale": "en-US",
  "timezone": "America/Los_Angeles",
  "active": true,
  "password": "t1meMasheen",
  "emails": [
    {
      "value": "bjensen@example.com",
      "type": "work",
      "primary": true
    },
    {
      "value": "babs@jensen.org",
      "type": "home",
      "primary": false
    }
  ],
  "phoneNumbers": [
    {
      "value": "555-555-5555",
      "type": "work",
      "primary": false
    },
    {
      "value": "555-555-4444",
      "type": "mobile",
      "primary": false
    }
  ],
  "ims": [
    {
      "type": "aim",
      "value": "someaimhandle"
    }
  ],
  "photos": [
    {
      "value": "https://photos.example.com/profilephoto/72930000000Ccne/F",
      "type": "photo"
    },
    {
      "value": "https://photos.example.com/profilephoto/72930000000Ccne/T",
      "type": "thumbnail"
    }
  ],
  "addresses": [
    {
      "formatted": "100 Universal City Plaza\nHollywood, CA 91608 USA",
      "streetAddress": "100 Universal City Plaza",
      "locality": "Hollywood",
      "region": "CA",
      "postalCode": "91608",
      "country": "USA",
      "type": "work",
      "primary": true
    },
    {
      "formatted": "456 Hollywood Blvd\nHollywood, CA 91608 USA",
      "streetAddress": "456 Hollywood Blvd",
      "locality": "Hollywood",
      "region": "CA",
      "postalCode": "91608",
      "country": "USA",
      "type": "home",
      "primary": false
    }
  ],
  "groups": [
    {
      "value": "e9e30dba-f08f-4109-8486-d5c6a331660a",
      "display": "Tour Guides",
      "type": "direct",
      "${'$'}ref": "../Groups/e9e30dba-f08f-4109-8486-d5c6a331660a"
    },
    {
      "value": "fc348aa8-3835-40eb-a20b-c726e15c55b5",
      "display": "Employees",
      "type": "direct",
      "${'$'}ref": "../Groups/fc348aa8-3835-40eb-a20b-c726e15c55b5"
    },
    {
      "value": "71ddacd2-a8e7-49b8-a5db-ae50d0a5bfd7",
      "display": "US Employees",
      "type": "direct",
      "${'$'}ref": "../Groups/71ddacd2-a8e7-49b8-a5db-ae50d0a5bfd7"
    }
  ],
  "entitlements": [],
  "roles": [],
  "x509Certificates": [
    {
      "value": "MIIDQzCCAqygAwIBAgICEAAwDQYJKoZIhvcNAQEFBQAwTjELMAkGA1UEBhMCVVMx\n                EzARBgNVBAgMCkNhbGlmb3JuaWExFDASBgNVBAoMC2V4YW1wbGUuY29tMRQwEgYD\n                VQQDDAtleGFtcGxlLmNvbTAeFw0xMTEwMjIwNjI0MzFaFw0xMjEwMDQwNjI0MzFa\n                MH8xCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRQwEgYDVQQKDAtl\n                eGFtcGxlLmNvbTEhMB8GA1UEAwwYTXMuIEJhcmJhcmEgSiBKZW5zZW4gSUlJMSIw\n                IAYJKoZIhvcNAQkBFhNiamVuc2VuQGV4YW1wbGUuY29tMIIBIjANBgkqhkiG9w0B\n                AQEFAAOCAQ8AMIIBCgKCAQEA7Kr+Dcds/JQ5GwejJFcBIP682X3xpjis56AK02bc\n                1FLgzdLI8auoR+cC9/Vrh5t66HkQIOdA4unHh0AaZ4xL5PhVbXIPMB5vAPKpzz5i\n                PSi8xO8SL7I7SDhcBVJhqVqr3HgllEG6UClDdHO7nkLuwXq8HcISKkbT5WFTVfFZ\n                zidPl8HZ7DhXkZIRtJwBweq4bvm3hM1Os7UQH05ZS6cVDgweKNwdLLrT51ikSQG3\n                DYrl+ft781UQRIqxgwqCfXEuDiinPh0kkvIi5jivVu1Z9QiwlYEdRbLJ4zJQBmDr\n                SGTMYn4lRc2HgHO4DqB/bnMVorHB0CC6AV1QoFK4GPe1LwIDAQABo3sweTAJBgNV\n                HRMEAjAAMCwGCWCGSAGG+EIBDQQfFh1PcGVuU1NMIEdlbmVyYXRlZCBDZXJ0aWZp\n                Y2F0ZTAdBgNVHQ4EFgQU8pD0U0vsZIsaA16lL8En8bx0F/gwHwYDVR0jBBgwFoAU\n                dGeKitcaF7gnzsNwDx708kqaVt0wDQYJKoZIhvcNAQEFBQADgYEAA81SsFnOdYJt\n                Ng5Tcq+/ByEDrBgnusx0jloUhByPMEVkoMZ3J7j1ZgI8rAbOkNngX8+pKfTiDz1R\n                C4+dx8oU6Za+4NJXUjlL5CvV6BEYb1+QAEJwitTVvxB/A67g42/vzgAtoRUeDov1\n                +GFiBZ+GNF/cAYKcMtGcrs2i97ZkJMo="
    }
  ],
  "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {
    "employeeNumber": "701984",
    "costCenter": "4130",
    "organization": "Universal Studios",
    "division": "Theme Park",
    "department": "Tour Operations",
    "manager": {
      "value": "26118915-6090-4610-87e4-49d8ca9f808d",
      "display": "John Smith",
      "type": "direct",
      "${'$'}ref": "../Users/26118915-6090-4610-87e4-49d8ca9f808d"
    }
  }
}
    """.trimIndent()
}