# Structure

## Core

The basis of a server (dependencies, http endpoints, websockets, async tasks, and schedules) are defined in core.

This doesn't have any specific dependencies, nor does it have typed server endpoint tools or authentication.  This module just gives enough structure to define a server which can be run by one of the engines.

### Engine - Ktor
### Engine - AWS Lambda
### Engine - Azure (out of date)
## Files, including local
### AWS S3
### Azure Blob Storage
### SFTP
### Scanner - ClamAV
## Database
### MongoDB
### PostgreSQL
### Annotation Processor
## Cache and PubSub
### Memcached
### Redis
### DynamoDB
## Notifications
### FCM
## Email, including SMTP
### Mailgun

## Typed

Contains tools for defining a server that is self-documenting.

When using this package, you can define endpoints using `.api(...)`, where you can provide documentation, including serialization information.

Also allows you to generate SDKs and OpenAPI specs from your server.

## Authentication

Uses `Typed` to define authentication endpoints and methods.