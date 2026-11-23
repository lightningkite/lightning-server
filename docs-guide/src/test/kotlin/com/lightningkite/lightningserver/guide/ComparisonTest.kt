package com.lightningkite.lightningserver.guide

import com.lightningkite.lightningserver.guide.samples.comparison.ArticleServerTest
import kotlin.test.Test

class ComparisonTest {
    @Test
    fun `comparison pages' ArticleServer lists published articles and runs its signal`() =
        ArticleServerTest().recentListsOnlyPublished()
}
