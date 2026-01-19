// by Claude
package com.lightningkite.lightningserver.typed.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for casing utility functions.
 */
class CasingUtilsTest {

    // ========== titleCase Tests ==========

    @Test
    fun `titleCase from camelCase`() {
        assertEquals("Hello World", "helloWorld".titleCase())
    }

    @Test
    fun `titleCase from PascalCase`() {
        assertEquals("Hello World", "HelloWorld".titleCase())
    }

    @Test
    fun `titleCase from snake_case`() {
        assertEquals("Hello World", "hello_world".titleCase())
    }

    @Test
    fun `titleCase from kebab-case`() {
        assertEquals("Hello World", "hello-world".titleCase())
    }

    @Test
    fun `titleCase from already Title Case`() {
        assertEquals("Hello World", "Hello World".titleCase())
    }

    // ========== spaceCase Tests ==========

    @Test
    fun `spaceCase from camelCase`() {
        assertEquals("hello World", "helloWorld".spaceCase())
    }

    @Test
    fun `spaceCase from PascalCase`() {
        assertEquals("hello World", "HelloWorld".spaceCase())
    }

    @Test
    fun `spaceCase from snake_case`() {
        assertEquals("hello world", "hello_world".spaceCase())
    }

    // ========== kabobCase Tests ==========

    @Test
    fun `kabobCase from camelCase`() {
        assertEquals("hello-world", "helloWorld".kabobCase())
    }

    @Test
    fun `kabobCase from PascalCase`() {
        assertEquals("hello-world", "HelloWorld".kabobCase())
    }

    @Test
    fun `kabobCase from snake_case`() {
        assertEquals("hello-world", "hello_world".kabobCase())
    }

    @Test
    fun `kabobCase from Title Case`() {
        assertEquals("hello-world", "Hello World".kabobCase())
    }

    // ========== snakeCase Tests ==========

    @Test
    fun `snakeCase from camelCase`() {
        assertEquals("hello_world", "helloWorld".snakeCase())
    }

    @Test
    fun `snakeCase from PascalCase`() {
        assertEquals("hello_world", "HelloWorld".snakeCase())
    }

    @Test
    fun `snakeCase from kebab-case`() {
        assertEquals("hello_world", "hello-world".snakeCase())
    }

    @Test
    fun `snakeCase from Title Case`() {
        assertEquals("hello_world", "Hello World".snakeCase())
    }

    // ========== screamingSnakeCase Tests ==========

    @Test
    fun `screamingSnakeCase from camelCase`() {
        assertEquals("HELLO_WORLD", "helloWorld".screamingSnakeCase())
    }

    @Test
    fun `screamingSnakeCase from PascalCase`() {
        assertEquals("HELLO_WORLD", "HelloWorld".screamingSnakeCase())
    }

    @Test
    fun `screamingSnakeCase from snake_case`() {
        assertEquals("HELLO_WORLD", "hello_world".screamingSnakeCase())
    }

    // ========== camelCase Tests ==========

    @Test
    fun `camelCase from PascalCase`() {
        assertEquals("helloWorld", "HelloWorld".camelCase())
    }

    @Test
    fun `camelCase from snake_case`() {
        assertEquals("helloWorld", "hello_world".camelCase())
    }

    @Test
    fun `camelCase from kebab-case`() {
        assertEquals("helloWorld", "hello-world".camelCase())
    }

    @Test
    fun `camelCase from already camelCase`() {
        assertEquals("helloWorld", "helloWorld".camelCase())
    }

    // ========== pascalCase Tests ==========

    @Test
    fun `pascalCase from camelCase`() {
        assertEquals("HelloWorld", "helloWorld".pascalCase())
    }

    @Test
    fun `pascalCase from snake_case`() {
        assertEquals("HelloWorld", "hello_world".pascalCase())
    }

    @Test
    fun `pascalCase from kebab-case`() {
        assertEquals("HelloWorld", "hello-world".pascalCase())
    }

    @Test
    fun `pascalCase from already PascalCase`() {
        assertEquals("HelloWorld", "HelloWorld".pascalCase())
    }

    // ========== functionCase Tests ==========

    @Test
    fun `functionCase from snake_case`() {
        assertEquals("getUserById", "get_user_by_id".functionCase())
    }

    @Test
    fun `functionCase filters special characters`() {
        val result = "get@User#By\$Id".functionCase()
        assertEquals("getUserById", result)
    }

    @Test
    fun `functionCase drops leading digits`() {
        val result = "123getUser".functionCase()
        assertEquals("getUser", result)
    }

    @Test
    fun `functionCase allows internal digits`() {
        val result = "get123User".functionCase()
        assertEquals("get123User", result)
    }

    // ========== Edge Cases ==========

    @Test
    fun `empty string stays empty`() {
        assertEquals("", "".camelCase())
        assertEquals("", "".pascalCase())
        assertEquals("", "".snakeCase())
    }

    @Test
    fun `single character`() {
        assertEquals("A", "a".pascalCase())
        assertEquals("a", "A".camelCase())
    }

    @Test
    fun `multiple separators`() {
        assertEquals("helloWorld", "hello__world".camelCase())
        assertEquals("helloWorld", "hello--world".camelCase())
    }

    @Test
    fun `mixed separators`() {
        assertEquals("helloWorld", "hello_-world".camelCase())
    }

    @Test
    fun `all uppercase word`() {
        val result = "APIResponse".camelCase()
        // Should handle consecutive uppercase gracefully
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `numbers in middle`() {
        // HTTP2Protocol -> the result depends on how consecutive caps are handled
        val result = "HTTP2Protocol".camelCase()
        assertTrue(result.isNotEmpty())
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}
