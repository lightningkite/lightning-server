package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.notifications.notificationsTest
import kotlin.test.Test

class NotificationsTest {
    @Test
    fun `a new shipment emails and pushes its customer`() { notificationsTest() }
}
