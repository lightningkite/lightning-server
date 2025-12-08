# Lightning Server Module Architecture

This document provides a visual overview of the module structure and dependencies in Lightning Server.

## Module Dependency Graph

```mermaid
graph TB
    %% Shared (Multiplatform) Modules
    subgraph shared["Multiplatform Modules (KMP)"]
        core-shared["core-shared<br/><i>Base types & models</i>"]
        auth-shared["auth-shared<br/><i>Auth types</i>"]
        typed-shared["typed-shared<br/><i>API types</i>"]
        files-shared["files-shared<br/><i>File types</i>"]
        media-shared["media-shared<br/><i>Media types</i>"]
        sessions-shared["sessions-shared<br/><i>Session types</i>"]
    end

    %% JVM Implementation Modules
    subgraph jvm["JVM Implementation Modules"]
        core["core<br/><i>HTTP, serialization,<br/>settings</i>"]
        auth["auth<br/><i>Authentication</i>"]
        typed["typed<br/><i>Type-safe APIs,<br/>OpenAPI</i>"]
        files["files<br/><i>File uploads</i>"]
        media["media<br/><i>Image processing</i>"]
        sessions["sessions<br/><i>Session management</i>"]
    end

    %% Session Extension Modules
    subgraph session-ext["Session Extensions"]
        sessions-email["sessions-email<br/><i>Email magic links</i>"]
        sessions-sms["sessions-sms<br/><i>SMS authentication</i>"]
    end

    %% Engine Modules
    subgraph engines["Engine Implementations"]
        engine-local["engine-local<br/><i>Testing engine</i>"]
        engine-ktor["engine-ktor<br/><i>Ktor HTTP server</i>"]
        engine-netty["engine-netty<br/><i>Netty HTTP server</i>"]
        engine-jdk["engine-jdk-server<br/><i>JDK HTTP server</i>"]
        engine-aws["engine-aws-serverless<br/><i>AWS Lambda +<br/>Terraform</i>"]
    end

    %% Other Modules
    secret-source-aws["secret-source-aws<br/><i>AWS Secrets Manager</i>"]
    demo["demo<br/><i>Example application</i>"]

    %% Shared module dependencies
    auth-shared --> core-shared
    typed-shared --> core-shared
    typed-shared --> auth-shared
    files-shared --> typed-shared
    media-shared --> files-shared
    sessions-shared --> core-shared
    sessions-shared --> auth-shared
    sessions-shared --> typed-shared

    %% JVM implementation dependencies on shared
    core --> core-shared
    auth --> auth-shared
    auth --> core
    typed --> typed-shared
    typed --> core
    typed --> auth
    files --> files-shared
    files --> typed
    media --> media-shared
    media --> files
    sessions --> sessions-shared
    sessions --> core
    sessions --> auth
    sessions --> typed

    %% Session extensions
    sessions-email --> sessions
    sessions-sms --> sessions

    %% Engine dependencies
    engine-local --> core
    engine-ktor --> core
    engine-ktor --> engine-local
    engine-netty --> core
    engine-netty --> engine-local
    engine-jdk --> core
    engine-jdk --> engine-local
    engine-aws --> core

    %% Other dependencies
    secret-source-aws --> core

    %% Demo dependencies (simplified)
    demo --> core
    demo --> engine-ktor
    demo --> engine-netty
    demo --> engine-jdk
    demo --> engine-aws
    demo --> typed
    demo --> sessions
    demo --> sessions-email
    demo --> sessions-sms
    demo --> files

    %% Styling
    classDef sharedStyle fill:#e1f5ff,stroke:#0288d1,stroke-width:2px
    classDef jvmStyle fill:#fff3e0,stroke:#f57c00,stroke-width:2px
    classDef engineStyle fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px
    classDef sessionStyle fill:#e8f5e9,stroke:#388e3c,stroke-width:2px
    classDef otherStyle fill:#fce4ec,stroke:#c2185b,stroke-width:2px

    class core-shared,auth-shared,typed-shared,files-shared,media-shared,sessions-shared sharedStyle
    class core,auth,typed,files,media,sessions jvmStyle
    class engine-local,engine-ktor,engine-netty,engine-jdk,engine-aws engineStyle
    class sessions-email,sessions-sms sessionStyle
    class secret-source-aws,demo otherStyle
```

## Module Categories

### Multiplatform Modules (KMP)
These modules use Kotlin Multiplatform and can target JVM, JS, iOS, and other platforms. They contain shared data models and types.

- **core-shared**: Base types (LSError, HttpMethod, MultiplexMessage) shared between client and server
- **auth-shared**: Authentication-related types
- **typed-shared**: API endpoint types and definitions
- **files-shared**: File-related types
- **media-shared**: Media processing types
- **sessions-shared**: Session management types

### JVM Implementation Modules
These modules provide JVM-specific implementations and server functionality.

- **core**: HTTP handling, serialization, settings management, service abstractions
- **auth**: Authentication implementation with database support
- **typed**: Type-safe API endpoints with OpenAPI/SDK generation
- **files**: File upload/download handling with multiple storage backends
- **media**: Image processing using Scrimage
- **sessions**: Session management with cache and database support

### Session Extensions
Authentication method implementations built on top of the sessions module.

- **sessions-email**: Email-based authentication (magic links)
- **sessions-sms**: SMS-based authentication (PIN codes)

### Engine Implementations
Different deployment targets for Lightning Server applications.

- **engine-local**: In-memory engine for unit testing
- **engine-ktor**: Ktor-based HTTP server (recommended for development)
- **engine-netty**: Netty-based HTTP server
- **engine-jdk-server**: Pure JDK HTTP server
- **engine-aws-serverless**: AWS Lambda deployment with automatic Terraform generation

### Other Modules

- **secret-source-aws**: AWS Secrets Manager integration
- **demo**: Example application showcasing all features

## Paired Module Pattern

Lightning Server follows a **paired module pattern** where most features have both:
1. A **-shared** multiplatform module (data models, types)
2. A **JVM implementation** module (server logic, database, services)

This allows:
- Client applications (JS, iOS, Android) to use shared types
- Server applications to use full JVM implementations
- Type-safe communication between client and server
- Automatic SDK generation with consistent types

## Dependency Principles

1. **Shared modules** depend only on other shared modules
2. **JVM modules** depend on their corresponding shared module
3. **Higher-level modules** depend on lower-level modules (e.g., files → typed → auth → core)
4. **Engines** depend on core (and sometimes engine-local for testing utilities)
5. **Extensions** depend on their base module (e.g., sessions-email → sessions)

## Building and Testing

```bash
# Build all modules
./gradlew build

# Build a specific module
./gradlew :core:build

# Test all modules
./gradlew test

# Test a specific module
./gradlew :typed:test
```
