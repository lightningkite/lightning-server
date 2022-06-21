# Ktor Batteries Documentation
Ktor Batteries Server has a lot of nifty features. Here is a brief list of things to get you started.

## Server Settings

- `loadSettings()` loads server settings

### Email

- `email.send()` sends an email


## Database
Ktor Batteries provides a `database` singleton that is used to handle the database. `FieldCollection`s are used to store information into the database.

- `database.collection()` returns a `FieldCollection` from the database
- `FieldCollection.withPermissions()` specifies access permissions for the collection
- `autoCollection()` is an automatic system that provides a simple web interface and default endpoints for managing a `FieldCollection`

Once you have a collection from the database, you can perform a number of operations on it to read or write data.

- `FieldCollection.insertOne()` adds an item to the collection
- `FieldCollection.get()` gets the item with a given id
- `FieldCollection.find()` gets multiple items from the collection based on a `condition`
- `FieldCollection.findOneAndUpdateById()` modifies the item with a given id

You can also specify additional code to be run when an object is added to a collection.

- `FieldCollection.postCreate()` specifies code to be run after an item is added to the collection

### Conditions
To test against existing data in the database, you can use `condition`s. This lambda expression can be inserted into `FieldCollection.find()` and similar calls to specify the condition to be executed.

### Modifications
To modify existing data in the database, you can use `modification`s. This lambda expression can be inserted into `FieldCollection.findOneAndUpdateById()` and similar calls to specify the modification to take place.

## Models
Models are used to access data from the `database` as well as from the body of http calls.

- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a model interchangeable with data in the database

You can inherit from multiple pre-defined interfaces to add some basic functionality.

- `HasId` is an interface that provides an id (*this id is the one tested against in `FieldCollection.get()` and other similar calls*)
- `HasEmail` is an interface provides an email address

## Authentication

- `configureAuth()` is used to configure authentication across the server

### Tokens
Tokens can be used to authenticate http calls across the server.

- `makeToken()` creates a token.
- `checkToken()` validates an existing token.

You can add additional information to the token using:

- `withSubject()`
- `withClaim()`

These can be used to store information relevant to the token across different routes.
You can parse a token validated with `checkToken()` to retrieve that information using the associated get methods (*e.g. `getClaim()` to access the claim attribute set with `withClaim()`*).

## Convenience

- `apiHelp()` auto-generates basic documentation on a server's endpoints
