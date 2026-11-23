package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.orderPubSubTest
import com.lightningkite.lightningserver.guide.samples.sharedPubSubTest
import kotlin.test.Test

class PubSubTest {
    @Test fun `a waiting request receives the published event`() { orderPubSubTest() }
    @Test fun `bound engine pubsub is the app pubsub`() { sharedPubSubTest() }
}
