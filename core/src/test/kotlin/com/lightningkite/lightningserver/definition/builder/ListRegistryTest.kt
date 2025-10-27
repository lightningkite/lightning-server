package com.lightningkite.lightningserver.definition.builder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListRegistryTest {
    
    @Test
    fun `test register adds items`() {
        val registry = ListRegistry<String>()
        assertTrue(registry.isEmpty())
        
        registry.register("a")
        assertEquals(listOf("a"), registry)
        
        registry.register("b")
        assertEquals(listOf("a", "b"), registry)
    }
    
    @Test
    fun `test include adds multiple items`() {
        val registry = ListRegistry<String>()
        registry.include(listOf("a", "b", "c"))
        
        assertEquals(listOf("a", "b", "c"), registry)
    }
    
    @Test
    fun `test registry preserves order`() {
        val registry = ListRegistry<Int>()
        registry.register(3)
        registry.register(1)
        registry.register(2)
        
        assertEquals(listOf(3, 1, 2), registry)
    }
    
    @Test
    fun `test duplicate items are allowed`() {
        val registry = ListRegistry<String>()
        registry.register("a")
        registry.register("a")
        registry.register("a")
        
        assertEquals(listOf("a", "a", "a"), registry)
    }
    
    @Test
    fun `test buildListRegistry creates immutable list`() {
        val list = buildListRegistry<String> {
            register("a")
            register("b")
            register("c")
        }
        
        assertEquals(listOf("a", "b", "c"), list)
    }
    
    @Test
    fun `test ListRegistry constructor with initial items`() {
        val registry = ListRegistry(listOf("a", "b"))
        assertEquals(listOf("a", "b"), registry)
        
        registry.register("c")
        assertEquals(listOf("a", "b", "c"), registry)
    }
}
