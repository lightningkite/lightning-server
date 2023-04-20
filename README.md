# Lightning Server

A project that drastically speeds up server development.  Comparable to Django for Python.

Built to work for all common serverless platforms.

## Major Features

- Abstractions
  - Multiple backends - AWS, Azure, Ktor
    - Generates Terraform for AWS
  - Database Abstraction backed by KotlinX Serialization
    - Supports MongoDB, partial Postgres, local JSON storage
    - Also supports a RAM mock for unit testing
  - Cache Abstraction
    - Supports Redis, Memcached, DynamoDB
    - Also supports a RAM mock for unit testing
  - Email Abstraction
    - Supports SMTP, Amazon SES
    - Also supports a console mock for testing
  - SMS Abstraction
    - Supports Twilio and a console mock for testing
  - File System Abstraction
    - Supports Local, AWS S3, and Azure Blob Storage
- Easy Server Definitions
  - Simple HTTP Endpoint Definition
  - Typed API Endpoints
    - Typed input, output, and user
    - Supports many content types, including JSON, BSON, CBOR, CSV, FormData
  - Event-Based Websocket Definition
  - Scheduled tasks
  - Asynchronous tasks
  - Permission rules for users accessing databases
- Pre-built Route Sets
  - REST endpoints with permissions
  - Authentication endpoints
    - Email Magic Links
    - Email PIN
    - SMS PIN
    - Password
    - OAuth for Google, Apple, and GitHub
- Server management tools
  - Built-in database admin and endpoint tester 
  - Health check page
  - Built-in OpenAPI documentation
  - Automatically generated documentation for API
  - Automatically generated SDKs for TypeScript and Kotlin

## Documentation

- [Set Up](docs/setup.md)
- [Client Documentation](docs/use-as-client.md)
- [Documentation (old)](documentation.md)
- [Quick Function List (old)](docs-feature-list.md)
- [Demo Project (old)](demo/src/main/kotlin)

## Road Map

- [ ] Improved per-environment Terraform generation
- [ ] Key-path ordering and grouping
- [ ] Additional Documentation
- [ ] Tutorial

### When Requested

- [ ] Complete Azure Support
  - [X] CosmosDB (using MongoDB interface)
  - [X] Http Endpoints
  - [ ] WebSockets
  - [ ] Scheduled Tasks
  - [ ] Asynchronous Tasks
  - [ ] Generate Terraform

### Specifically Not Planned

- DynamoDB as Database Support - DynamoDB is unfortunately far too limited to fit our current abstraction.  If scan is able to be done in reverse in the future, this may become possible.
