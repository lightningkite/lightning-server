# Ktor Batteries Server Feature Docs
Ktor Batteries Server has a lot of nifty features. Here is a list of things to get you started.

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
- `autoCollection()` automatically provides a simple web interface and default endpoints for managing a `FieldCollection`

## FieldCollection
`FieldCollection` is a collection used to store similar information to the database.

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

- `Equal()` returns true if the given values are equivalent
- `NotEqual()` returns true if the given values are not equivalent
- `GreaterThan()` returns true if the given number is greater than another number
- `LessThan()` returns true if the given number is less than another number
- `GreaterThanOrEqual()` returns true if the given number is greater than or equal to another number
- `LessThanOrEqual()` returns true if the given number is less than or equal to another number

*Shorthands:*

- `eq` is shorthand for `Equal()`
- `neq` is shorthand for `NotEqual()`
- `ne` is shorthand for `NotEqual()`
- `gt` is shorthand for `GreaterThan()`
- `lt` is shorthand for `LessThan()`
- `gte` is shorthand for `GreaterThanOrEqual()`
- `lte` is shorthand for `LessThanOrEqual()`

#### Relativity

- `Inside()` returns true if the given value is inside a given list
- `NotInside()` returns true if the given value is not inside a given list
- `Search()`

*Shorthands:*

- `inside` is shorthand for to `Inside()`
- `nin` is shorthand for `NotInside()`
- `notIn` is shorthand for `NotInside()`
- `contains` is shorthand for `Search()`

#### Bitwise

- `IntBitsClear()`
- `IntBitsSet()`
- `IntBitsAnyClear()`
- `IntBitsAnySet()`

*Shorthands:*

- `allClear` is shorthand for `IntBitsClear()`
- `allSet` is shorthand for `IntBitsSet()`
- `anyClear` is shorthand for `IntBitsAnyClear()`
- `anySet` is shorthand for `IntBitsAnySet()`

#### Miscellaneous

- `AllElements()` returns true if the given `condition` is true for all elements in a given list
- `AnyElements()` returns true if the given `condition` is true for any element in a given list
- `SizesEquals()` returns true if the given integer is equal to the size of a given list
- `Exists()` returns true if the given key is inside a given map
- `OnKey()`
- `OnField()`
- `IfNotNull()` returns true if the given value is not null

*Shorthands:*

- `all` is shorthand for `AllElements()`
- `any` is shorthand for `AnyElements()`
- `sizesEquals` is shorthand for `SizesEquals()`
- `containsKey` is shorthand for `Exists()`

## Modification
To modify existing data in the database, you can use a `modification`.

### Members

- `then` strings multiple modification calls together

#### Set

- `Assign()` sets the given value to another value
- `CoerceAtMost()` restricts a given number to a given maximum
- `CoerceAtLeast()` restricts a given number to a given minimum
- `Increment()` increments a given number by another number
- `Multiply()` multiplies a given number by another number

*Shorthands:*

- `assign` is shorthand for `Assign()`
- `coerceAtMost` is shorthand for `CoerceAtMost()`
- `coerceAtLeast` is shorthand for `CoerceAtLeast()`
- `plus` is shorthand for `Increment()`
- `times` is shorthand for `Multiply()`

#### Array Operations

- `AppendString()` appends a given string to another string
- `AppendList()` appends a given list to another list
- `AppendSet()` appends a given set to another set
- `Remove()` removes elements from a given list based on a `condition`
- `RemoveInstances()` removes elements from a given list that are equivalent to elements from another list
- `DropFirst()` removes the first element from a given list
- `DropLast()` removes the last element from a given list

*Shorthands:*

- `plus` is shorthand for `AppendString()`
- `plus` is shorthand for `AppendList()`
- `addAll` is shorthand for `AppendList()`
- `addUnique` is shorthand for `AppendSet()`
- `removeAll` is shorthand for `Remove()`
- `removeAll` is shorthand for `RemoveInstances()`

## Models
Models are used to access data from the `database` as well as from the body of http calls.

- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a model interchangeable with data in the database

### Interfaces
You can inherit functionality from multiple pre-defined interfaces.

- `HasId` provides an id
- `HasEmail` provides an email address

## Authentication

### Tokens
Tokens can be used to authenticate http calls across the server.

- `makeToken()` creates a token
- `checkToken()` validates an existing token
- `withClaim()` adds a claim to the token
- `withSubject()` adds a claim with the key "subject" to the token
- `getClaim()` gets a claim from a validated token
- `getSubject()` gets a claim with the key "subject" from a validated token

## Email

- `email.send()` sends an email

## Miscellaneous

- `apiHelp()` auto-generates basic documentation on a server's endpoints
