# Lightning Server

A project that drastically speeds up server development by automating the creation of RESTful APIs from models.

Built to work for all common serverless platforms.

## Major Features

- Abstractions
  - Multiple backends - AWS, Azure, Ktor
    - Generates Terraform for AWS
  - Database Abstraction backed by KotlinX Serialization
    - Supports MongoDB currently, built to expand in the future
    - Also supports a RAM mock for unit testing
  - Cache Abstraction
    - Supports Redis, Memcached
    - Also supports a RAM mock for unit testing
  - Email Abstraction
    - Supports SMTP, Amazon SES
    - Also supports a console mock for testing
  - SMS Abstraction
    - Supports Twilio and a console mock for testing
- Easy Server Definitions
  - Simple HTTP Endpoint Definition
  - Typed API Endpoints
    - Typed input, output, and user
    - Supports many content types
  - Event-Based Websocket Definition
  - Scheduled tasks
  - Asynchronous tasks
- Quick generation of model-based endpoints
  - REST endpoints
  - Authentication endpoints
  - admin pages for managing models and debugging
- Server management tools
  - Health check page
  - Automatically generated documentation for API

## Documentation

- [Documentation](documentation.md)
- [Quick Function List](docs-feature-list.md)
- [Demo Project](demo/src/main/kotlin)

## Road Map

- [ ] Terraform AWS Test Mode (fewer availability zones for cheaper)
- [ ] PostgreSQL Support (Aurora on AWS)
- [ ] DynamoDB as Cache Support
- [ ] Cleaner Terraform for AWS

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
