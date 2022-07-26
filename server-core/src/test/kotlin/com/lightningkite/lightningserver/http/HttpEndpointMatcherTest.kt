package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import org.junit.Assert.*
import org.junit.Test

class HttpEndpointMatcherTest {
    @Test
    fun test() {
        val root = ServerPath.root.get
        val test = ServerPath("test").get
        val testSlash = ServerPath("test/").get
        val testA = ServerPath("test/a").get
        val testAB = ServerPath("test/a/b").get
        val testXB = ServerPath("test/{first}/b").get
        val testXC = ServerPath("test/{otherName}/c").get
        val testAX = ServerPath("test/a/{second}").get
        val testXXX = ServerPath("test/{...}").get
        val paths = setOf(
            root,
            test,
            testSlash,
            testA,
            testAB,
            testXB,
            testXC,
            testAX,
            testXXX,
        )
        val matcher = HttpEndpointMatcher(paths.asSequence())
        assertEquals(HttpEndpointMatcher.Match(root, mapOf(), null), matcher.match("", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(root, mapOf(), null), matcher.match("/", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(test, mapOf(), null), matcher.match("test", HttpMethod.GET))
        assertEquals(null, matcher.match("asdf", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testSlash, mapOf(), null), matcher.match("test/", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testA, mapOf(), null), matcher.match("test/a", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testAB, mapOf(), null), matcher.match("test/a/b", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testXB, mapOf("first" to "c"), null), matcher.match("test/c/b", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testXC, mapOf("otherName" to "c"), null), matcher.match("test/c/c", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testAX, mapOf("second" to "x"), null), matcher.match("test/a/x", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testXXX, mapOf(), "asdf/fdsa"), matcher.match("test/asdf/fdsa", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testXXX, mapOf(), "asdf/fdsa/"), matcher.match("test/asdf/fdsa/", HttpMethod.GET))
        assertEquals(HttpEndpointMatcher.Match(testXXX, mapOf(), "a/"), matcher.match("test/a/", HttpMethod.GET))
    }
}