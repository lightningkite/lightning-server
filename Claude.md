# Claude Code Instructions for Lightning Server

This document contains instructions and discovered techniques for Claude Code when working with the Lightning Server codebase.

## Reading Library/Dependency Sources

When you need to view the implementation of classes or functions from dependency libraries (e.g., `MediaType` from `com.lightningkite`), use the following technique:

1. **Use `get_symbol_info` to locate the source**: The JetBrains MCP server can provide the path to source JARs.
   ```
   mcp__jetbrains__get_symbol_info on the symbol will return declarationFile like:
   "../../.m2/repository/com/lightningkite/services/should-be-standard-library-jvm/0.0.1-69/should-be-standard-library-jvm-0.0.1-69-sources.jar!/commonMain/com/lightningkite/MediaType.kt"
   ```

2. **Extract and read the source file using unzip**:
   ```bash
   unzip -p /Users/jivie/.m2/repository/com/lightningkite/services/should-be-standard-library-jvm/0.0.1-69/should-be-standard-library-jvm-0.0.1-69-sources.jar commonMain/com/lightningkite/MediaType.kt
   ```

This works as long as `-sources.jar` files are available in the Maven repository (~/.m2/repository).

### Example Use Cases
- Understanding companion object values (e.g., all MediaType.Application.* values)
- Reading documentation and implementation details from dependencies
- Understanding how dependency classes are structured

## Project Dependencies

Key Lightning Kite service dependencies:
- `data-jvm` - Data structures and types
- `database-jvm` - Database abstractions
- `should-be-standard-library-jvm` - Common utilities including MediaType
- `files-jvm` - File handling
- `cache-jvm` - Caching abstractions
- Various implementation modules for MongoDB, PostgreSQL, S3, Redis, etc.

## Architecture Notes

- The project uses Ktor for HTTP server functionality
- Serialization is handled via kotlinx.serialization
- Media type handling is centralized through the `MediaType` class from `com.lightningkite`
- The codebase supports multiple deployment targets (AWS Lambda, JDK server, Ktor, Netty)
