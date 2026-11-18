package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.*
import kotlin.test.Test

class SerializationTest {
    @Test fun `raw handler negotiates JSON, form data and query parameters`() { serializationRawTest() }
    @Test fun `contextual serializer from externalSerialization is used for responses`() { serializationContextualTest() }
    @Test fun `custom media type coder is chosen by Accept`() { serializationCoderTest() }
    @Test fun `encoding interceptor can stop a response`() { serializationInterceptorTest() }
}
