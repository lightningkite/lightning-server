package com.lightningkite.lightningserver.core

import org.junit.Assert.*
import org.junit.Test

class ServerPathMatcherTest {
    @Test fun test() {
        val root = ServerPath.root
        val test = ServerPath("test")
        val testSlash = ServerPath("test/")
        val testA = ServerPath("test/a")
        val testAB = ServerPath("test/a/b")
        val testXB = ServerPath("test/{first}/b")
        val testXC = ServerPath("test/{otherName}/c")
        val testAX = ServerPath("test/a/{second}")
        val testXXX = ServerPath("test/{...}")
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
        val matcher = ServerPathMatcher(paths.asSequence())
        assertEquals(ServerPathMatcher.Match(root, mapOf(), null), matcher.match(""))
        assertEquals(ServerPathMatcher.Match(test, mapOf(), null), matcher.match("test"))
        assertEquals(ServerPathMatcher.Match(testSlash, mapOf(), null), matcher.match("test/"))
        assertEquals(ServerPathMatcher.Match(testA, mapOf(), null), matcher.match("test/a"))
        assertEquals(ServerPathMatcher.Match(testAB, mapOf(), null), matcher.match("test/a/b"))
        assertEquals(ServerPathMatcher.Match(testXB, mapOf("first" to "c"), null), matcher.match("test/c/b"))
        assertEquals(ServerPathMatcher.Match(testXC, mapOf("otherName" to "c"), null), matcher.match("test/c/c"))
        assertEquals(ServerPathMatcher.Match(testAX, mapOf("second" to "x"), null), matcher.match("test/a/x"))
        assertEquals(ServerPathMatcher.Match(testXXX, mapOf(), "asdf/fdsa"), matcher.match("test/asdf/fdsa"))
        assertEquals(ServerPathMatcher.Match(testXXX, mapOf(), "asdf/fdsa/"), matcher.match("test/asdf/fdsa/"))
        assertEquals(ServerPathMatcher.Match(testXXX, mapOf(), "a/"), matcher.match("test/a/"))
    }
}