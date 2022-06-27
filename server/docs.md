# Ktor Batteries Server Feature Docs
Ktor Batteries Server has a lot of nifty features. Here is a list of things to get you started, or to be used as a reference.

## Server Setup

- `runServer()` runs the server
- `loadSettings()` loads server settings
- `configureFiles()`
- `configureSerialization()`
- `configureExceptions()`
- `configureAuth()`
- `install()`

## Server Settings Singletons

- `GeneralServerSettings`
- `AuthSettings`
- `FilesSettings`
- `LoggingSettings`
- `DatabaseSettings`
- `ExceptionSettings`
- `EmailSettings`

## Database

- `database` is a singleton that handles the database
- `database.collection()` returns a `FieldCollection` from the database
- `autoCollection()` is a shortcut function for setting up restful endpoints for a `FieldCollection`, as well as an html admin portal

## FieldCollection
`FieldCollection` is an abstract class for interacting with a database, and on a specific collection/table.

- `FieldCollection.withPermissions()` creates a `FieldCollection` with access permissions

### Members

#### Insert

- `insertOne()` adds an element to the collection
- `insertMany()` adds multiple elements to the collection

#### Get

- `get()` gets an element from the collection
- `getMany()` gets multiple elements from the collection
- `find()` gets multiple elements from the collection

#### Modify

- `updateOne()` modifies an element in the collection
- `updateMany()` modifies multiple elements in the collection
- `updateOneById()` modifies an element in the collection
- `findOneAndUpdate()` modifies an element in the collection
- `findOneAndUpdateById()` modifies an element in the collection

#### Replace

- `replaceOne()` replaces an element in the collection
- `replaceOneById()` replaces an element in the collection

#### Delete

- `deleteOne()` deletes an element in the collection
- `deleteMany()` deletes multiple elements in the collection
- `deleteOneById()` deletes an element in the collection

#### Upsert

- `upsertOne()`
- `upsertOneById()`

#### Execution

- `postChange()`
- `preDelete()`
- `postDelete()`
- `preCreate()` executes code before an element is added to the collection
- `postCreate()` executes code after an element is added to the collection

## Condition
To test against existing data in the database, you can use a `condition`.

### Members

#### Comparison

- `eq` (equal) returns true if the given values are equivalent
- `neq` (not equal) returns true if the given values are not equivalent
- `ne` (not equal) returns true if the given values are not equivalent
- `gt` (greater than) returns true if the given number is greater than another number
- `lt` (less than) returns true if the given number is less than another number
- `gte` (greater than or equal) returns true if the given number is greater than or equal to another number
- `lte` (less than or equal) returns true if the given number is less than or equal to another number

#### Relativity

- `inside` returns true if the given value is inside a given list
- `nin` (not in) returns true if the given value is not inside a given list
- `notIn` returns true if the given value is not inside a given list
- `contains` is shorthand for `Search()`

#### Bitwise

- `allClear`
- `allSet`
- `anyClear`
- `anySet`

#### Miscellaneous

- `all` returns true if the given `condition` is true for all elements in a given list
- `any` returns true if the given `condition` is true for any element in a given list
- `sizesEquals` returns true if the given integer is equal to the size of a given list
- `containsKey` returns true if the given key is inside a given map
- `OnKey()`
- `OnField()`
- `IfNotNull()` returns true if the given value is not null

## Modification
To modify existing data in the database, you can use a `modification`.

### Members

- `then` strings multiple modification calls together

#### Set

- `assign` sets the given value to another value
- `coerceAtMost` restricts a given number to a given maximum
- `coerceAtLeast` restricts a given number to a given minimum
- `plus` increments a given number by another number
- `times` multiplies a given number by another number

#### Array Operations

- `plus` appends a given string to another string
- `plus` appends a given list to another list
- `addAll` is shorthand for `AppendList()`
- `addUnique` appends a given set to another set
- `removeAll` removes elements from a given list based on a `condition`
- `removeAll` removes elements from a given list that are equivalent to elements from another list
- `DropFirst()` removes the first element from a given list
- `DropLast()` removes the last element from a given list

## Models
Models are used to access data from the `database` as well as from the body of http calls.

- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a model interchangeable with data in the database

### Interfaces
You can inherit functionality from multiple pre-defined interfaces.

- `HasId` provides an id
- `HasEmail` provides an email address

## Tokens
Tokens can be used to authenticate http calls across the server.

- `makeToken()` creates a token
- `checkToken()` validates an existing token
- `withClaim()` adds a claim to the token
- `withSubject()` adds a claim with the key "subject" to the token
- `getClaim()` gets a claim from a validated token
- `getSubject()` gets a claim with the key "subject" from a validated token

## Exceptions
Ktor-Batteries provides exceptions you can use.

- `ForbiddenException()` responds with an http status code of forbidden
- `AuthenticationException()` responds with an http status code of unauthorized

## Email

- `email` is a singleton that handles server email
- `email.send()` sends an email

## Additional Convenience

- `apiHelp()` auto-generates basic documentation on the server's endpoints
