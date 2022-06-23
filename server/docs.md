# Ktor Batteries Server Feature Docs
Ktor Batteries Server has a lot of nifty features. Here is a list of things to get you started.

- `loadSettings()` loads server settings

## Settings singletons

- `GeneralServerSettings` configures general server settings
- `AuthSettings` configures server-wide authentication settings
- `FilesSettings` configures server file settings
- `LoggingSettings` configures server logging settings
- `DatabaseSettings` configures database settings
- `ExceptionSettings` configures server exception settings
- `EmailSettings` configures server email settings

## Server Setup

-  `configureFiles()`
-  `configureSerialization()`
-  `configureExceptions()`
-  `configureAuth()`
-  `install()`

## Database

- `database` is a singleton that handles the database
- `database.collection()` returns a `FieldCollection` from the database

### FieldCollection
`FieldCollection` is a collection used to store similar information to the database.

- `FieldCollection.withPermissions()` creates a `FieldCollection` with access permissions

#### Member Functions

##### Insert

- `insertOne()` adds an item to the collection
- `insertMany()` adds multiple items to the collection

##### Get

- `get()` gets an item from from collection
- `getMany()` gets multiple items from the collection
- `find()` gets multiple items from the collection

##### Modify

- `updateOne()` modifies an item in the collection
- `updateMany()` modifies multiple items in the collection
- `updateOneById()` modifies an item in the collection
- `findOneAndUpdate()` modifies an item in the collection
- `findOneAndUpdateById()` modifies an item in the collection

##### Replace

- `replaceOne()` replaces an item in the collection
- `replaceOneById()` replaces an item in the collection

##### Delete

- `deleteOne()` deletes an item in the collection
- `deleteMany()` deletes multiple items in the collection
- `deleteOneById()` deletes an item in the collection

##### Upsert

- `upsertOne()`
- `upsertOneById()`

##### Execution

- `postChange()`
- `preDelete()`
- `postDelete()`
- `preCreate()` executes code before an item is added to the collection
- `postCreate()` executes code after an item is added to the collection

#### Miscellaneous

- `autoCollection()` is an automatic system that provides a simple web interface and default endpoints for managing a `FieldCollection`

### Condition
To test against existing data in the database, you can use a `condition`.

#### Member Functions

##### Infix Function Shorthands

###### Comparison

- `eq` is shorthand for `Equal()`
- `neq` is shorthand for `NotEqual()`
- `ne` is shorthand for `NotEqual()`
- `gt` is shorthand for `GreaterThan()`
- `lt` is shorthand for `LessThan()`
- `gte` is shorthand for `GreaterThanOrEqual()`
- `lte` is shorthand for `LessThanOrEqual()`

###### Relativity

- `inside` is shorthand for to `Inside()`
- `nin` is shorthand for `NotInside()`
- `notIn` is shorthand for `NotInside()`
- `contains` is shorthand for `Search()`

###### Bitwise

- `allClear` is shorthand for `IntBitsClear()`
- `allSet` is shorthand for `IntBitsSet()`
- `anyClear` is shorthand for `IntBitsAnyClear()`
- `anySet` is shorthand for `IntBitsAnySet()`

###### Miscellaneous

- `all` is shorthand for `AllElements()`
- `any` is shorthand for `AnyElements()`
- `sizesEquals` is shorthand for `SizesEquals()`
- `containsKey` is shorthand for `Exists()`

### Modification
To modify existing data in the database, you can use a `modification`.

#### Member Functions

##### Infix Function Shorthands

###### Set

- `assign` is shorthand for `Assign()`
- `coerceAtMost` is shorthand for `CoerceAtMost()`
- `coerceAtLeast` is shorthand for `CoerceAtLeast()`
- `plus` is shorthand for `Increment()`
- `times` is shorthand for `Multiply()`

###### Array Operations

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
