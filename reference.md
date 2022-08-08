# LightningServer reference

LightningServer is chock-full of useful features. This list is designed to be used as a quick reference for those
already experienced with the library.

## Annotations

- `@file:UseContextualSerialization` enables contextual serialization for the file
- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a data class a database model

## Exceptions

Throwing these exceptions in the body of endpoints will automatically return the corresponding HTTP response.

- `BadRequestException()`
- `UnauthorizedException()`
- `ForbiddenException()`
- `NotFoundException()`

## Functions

- `prepareModels()`
- `setting()`
- `loadSettings()`
- `runServer()`

## HttpEndpoint

- `get` or `get()`
- `post` or `post()`
- `put` or `put()`
- `patch` or `patch()`
- `delete` or `delete()`
- `head` or `head()`
- `handler {}`
- `typed {}`
- `apiHelp()`

## Interfaces

- `HasId` provides a data class with an id
- `HasEmail` provides a data class with an email address

## ServerPath

- `routing {}`
- `path()`