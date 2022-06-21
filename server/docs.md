# Documentation
Ktor Batteries Server has a lot of nifty features. Here is a brief list of things to get you started.

## Database

- `database` is a singleton that contains the data from the database

`FieldCollections` are used to store information into the database.

- `database.collection()` is used to access a collection
- `database.collection.withPermissions()` is used to specify create/read/update/delete permissions to the collection

Once you have a collection from the database, you can perform a number of operations on it to read or write data.

- `database.collection.insertOne()` is used to add an item to the collection
- `database.collection.find()` is used to get multiple items from the collection based on a condition
- `database.collection.findOneAndUpdateById()` is used to get an item from the a collection and update it based on its id

You can also specify additional code to be run when an object is added to a collection.

- `database.collection().postCreate()` is used to run code after an item is added to the collection

## Email
The server can automate sending emails.

- `email.send()` is used to send emails

## Models

- `@Serializable` is used to make a data class serializable
- `@DatabaseModel` is used to make a model storable in the database

You can inherit from multiple pre-defined interfaces to add some basic functionality.

- The `HasId` interface provides a id field
- The `HasEmail` interface provides an email field

## Routing

- `autoCollection()` is used to handle the creation of objects within a collection
- `apiHelp()` is used to automatically generate documentation for ktor routes

## Server Settings

- `loadSettings()` is used to load an object with server settings into the settings file

## Tokens
Tokens are used to authenticate http calls across the server.

- `makeToken()` is used to create tokens.
- `checkToken()` is used to validate existing tokens.

You can add additional information to the token using:

- `withSubject()`
- `withClaim()`

These can be used to store information relevant to the token across different routes.
You can parse a token validated with `checkToken()` to retrieve that information using the associated get methods (*e.g. `getClaim()` to access the claim attribute set with `withClaim()`*).
