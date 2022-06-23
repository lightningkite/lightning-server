# Ktor Batteries Server Feature Docs
Ktor Batteries Server has a lot of nifty features. Here is a brief list of things to get you started.

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

- `FieldCollection.insertOne()` adds an item to the collection
- `FieldCollection.get()` gets the item with a given id
- `FieldCollection.find()` gets multiple items from the collection based on a `condition`
- `FieldCollection.findOneAndUpdateById()` modifies the item with a given id
- `FieldCollection.postCreate()` specifies code to be run after an item is added to the collection

#### Convenience

- `autoCollection()` is an automatic system that provides a simple web interface and default endpoints for managing a `FieldCollection`

### Condition
To test against existing data in the database, you can use a `condition`.

### Modification
To modify existing data in the database, you can use a `modification`.

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
