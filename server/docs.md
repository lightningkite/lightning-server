# Lightning Server feature docs

Lightning Server has a lot of nifty features. Here is a list of things to get you started, or just to be used as a reference.

`runServer()` runs the server

## Settings

**TODO: ADD DESCRIPTION**

Calling `setting()` loads a setting and returns a relavent object for later use. Here is an example of how you could load the [FilesSettings](#files) setting:

<pre><code>val files = setting(name = "files", default = FilesSettings())</code></pre>

Once you have set up your server's settings, you can call `loadSettings()` to generate a `settings.yaml` file or to use a preexisting one. If there is a discrepency between the `settings.yaml` file and the settings delared in your code a file with suggested settings will be generated and output to the project's root directory for your reference.

## Files

**TODO: ADD DESCRIPTION**

`FilesSettings` contains the default [settings](#settings) for server files.

## Databases

**TODO: ADD DESCRIPTION**

`DatabaseSettings` contains the default [settings](#settings) for a database.

## Authentication

JSON Web Tokens (JWT) are used to authenticate http calls across the server. You use a `JwtSigner` to create and verify these tokens.

`JwtSigner` contains the default [settings](#settings) for a token signer.

Once you have set up JwtSigners for your server, you can call `authEndpoints()` to generate a few endpoints to simplify server authentication.

### Members

- `token()` creates a token
- `verify()` validates an existing token

## Server Email

**TODO: ADD DESCRIPTION**

`EmailSettings` contains the default [settings](#settings) for the server's email.

### Members

- `send()` sends an email

## Field Collections

`FieldCollection` is an abstract class for interacting with a [database](#databases), and on a specific collection/table. You can access a `FieldCollection` by calling `collection()` on a database you created with [`setting()`](#settings). Here is an example of how to create a basic `FieldCollection` with access permissions for a [model](#models) `User`, given a database called `database`:

<pre><code>database().collection().withPermissions(
   ModelPermissions(
      //permissions here
   )
)</code></pre>

`FieldCollection.withPermissions()` creates a `FieldCollection` with access permissions.

### Members

- `insertOne()` adds an element to the collection
- `insertMany()` adds multiple elements to the collection
- `get()` gets an element from the collection
- `getMany()` gets multiple elements from the collection
- `findOne()` gets an element from the collection
- `find()` gets multiple elements from the collection
- `updateOne()` modifies an element in the collection
- `updateMany()` modifies multiple elements in the collection
- `updateOneById()` modifies an element in the collection
- `replaceOne()` replaces an element in the collection
- `replaceOneById()` replaces an element in the collection
- `deleteOne()` deletes an element in the collection
- `deleteMany()` deletes multiple elements in the collection
- `deleteOneById()` deletes an element in the collection
- `upsertOne()` modifies an element in the collection if it exists or creates a new element if it does not
- `upsertOneById()` modifies an element in the collection if it exists or creates a new element if it does not
- `postChange()` modifies an element after an element is added to the collection
- `preDelete()` deletes an element after an element is added to the collection
- `postDelete()` deletes an element after an element is added to the collection
- `preCreate()` executes code before an element is added to the collection
- `postCreate()` executes code after an element is added to the collection

## Data conditions

Conditions are used to test against [database models](#models) in a [database](#databases). They are written using infix functions. Here is a simple condition that tests the equivilancy of string in a database model and a string literal:

<pre><code>condition { user -> user.name eq "John" }</code></pre>

A single condition can also test against multiple conditions together using the operators `and` and `or`, which work as expected. Seperate conditions need to be enclosed in parentheses:

<pre><code>condition { user -> (user.name eq "John") and (user.id eq 42) }</code></pre>

Here is a list of the operators that `condition` provides:

- `and` returns true if both of the given conditions are true
- `or` returns true if either of the given conditions are true
- `not` returns true if the given condition is not true
- `eq` (equal) returns true if the given values are equivalent
- `neq` (not equal) returns true if the given values are not equivalent
- `ne` (not equal) returns true if the given values are not equivalent
- `gt` (greater than) returns true if the given number is greater than another number
- `lt` (less than) returns true if the given number is less than another number
- `gte` (greater than or equal) returns true if the given number is greater than or equal to another number
- `lte` (less than or equal) returns true if the given number is less than or equal to another number
- `inside` returns true if the given value is inside a given list
- `nin` (not in) returns true if the given value is not inside a given list
- `notIn` returns true if the given value is not inside a given list
- `contains` returns true if the given value is inside a given list
- `allClear` returns true if all of the given bits in a bitmask that corrospond to the given set (1) bits in another bitmask are clear (0)
- `allSet` returns true if all of the given bits in a bitmask that corrospond to the given set (1) bits in another bitmask are set (1)
- `anyClear` returns true if any of the given bits in a bitmask that corrospond to the given set (1) bits in another bitmask are clear (0)
- `anySet` returns true if any of the given bits in a bitmask that corrospond to the given set (1) bits in another bitmask are set (1)
- `all` returns true if the given `condition` is true for all elements in a given list
- `any` returns true if the given `condition` is true for any element in a given list
- `sizesEquals` returns true if the given integer is equal to the size of a given list
- `containsKey` returns true if the given key is inside a given map

## Data modifications

Modifications are used to modify existing [database models](#models) in a [database](#databases). Like [conditions](#Data conditions), they are written using infix functions. Here is a simple modification that sets a string to "John":

<pre><code>modification { user -> user.name assign "John" }</code></pre>

A single `modification` can also chain multiple modifications together using the operator `then`. Seperate modifications need to be enclosed in parentheses:

<pre><code>modification { user -> (user.name assign "John") then (user.id assign 42) }</code></pre>

Here is a list of the operators that `modification` provides:

- `then` strings multiple modification calls together
- `assign` sets the given value to another value
- `coerceAtMost` restricts a given number to a given maximum
- `coerceAtLeast` restricts a given number to a given minimum
- `plus` increments a given number by another number
- `times` multiplies a given number by another number
- `plus` appends a given string to another string
- `plus` appends a given list to another list
- `addAll` appends a given list to another list
- `addUnique` appends a given set to another set
- `removeAll` removes elements from a given list based on a `condition`
- `removeAll` removes elements from a given list that are equivalent to elements from another list
- `dropFirst()` removes the first element from a given list
- `dropLast()` removes the last element from a given list

## Models

Models are data classes used to access and serialize data from a [database](#databases) as well as from the bodies of http calls.

### Annotations

- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a data class interchangeable with data in the database

*Once you have set up your models, you can call `prepareModels()` to generate serialization code for each model with the `@Serializable` annotation.*

### Interfaces

A model can inherit functionality from multiple predefined interfaces:

- `HasId` provides an id field
- `HasEmail` provides an email address field

These fields also make it easier to use the model with other systems in Lightning Server. For example, models that inherit from `HasId` can utilize `withPermissions()` in [Field Collections](#field-collections)

## Exceptions

Lightning Server provides several exceptions you can use in the body of your endpoints. Throwing these exceptions will also automatically return the corrosponding http response.

- `BadRequestException()` responds with an http status code of 400
- `UnauthorizedException()` responds with an http status code of 401
- `ForbiddenException()` responds with an http status code of 403
- `NotFoundException()` responds with an http status code of 404

## Additional convenience

- `apiHelp()` auto-generates basic documentation on the server's endpoints
