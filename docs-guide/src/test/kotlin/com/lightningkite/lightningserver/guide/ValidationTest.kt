package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import com.lightningkite.services.data.MaxLength
import com.lightningkite.services.database.validation.AnnotationValidators
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationTest {
    @Test fun `typed endpoint rejects invalid input with validation-failed`() { validationTest() }
    @Test fun `invalid input over HTTP is a 400`() { validationHttpTest() }
    @Test fun `custom annotation is enforced`() { customValidationTest() }
    @Test fun `manual validation in a raw handler is a 400`() { manualValidationTest() }

    @Serializable
    data class Profile(@MaxLength(5) val bio: String)

    @Serializable
    data class Wrapper(val profile: Profile)

    // Backs the chapter's claims that element annotations cascade into lists and that nested objects are validated.
    @Test fun `element and nested constraints are checked`() = runBlocking {
        val tooLongTag = NewAccount(name = "A", handle = "abc", age = 20, tags = listOf("x".repeat(21)))
        assertEquals(setOf("tags.0"), AnnotationValidators.Standard.validate(NewAccount.serializer(), tooLongTag).keys)
        assertEquals(setOf("profile.bio"), AnnotationValidators.Standard.validate(Wrapper.serializer(), Wrapper(Profile("too long"))).keys)
    }
}
