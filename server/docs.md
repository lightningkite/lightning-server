# Lightning Server Feature Docs

**Lightning Server has a lot of nifty features. Here is a list of things to get you started, or to be used as a reference.**

`runServer()` run the server based on settings loaded from a `settings.yaml` file in the project's root directory

## Settings

**TODO: ADD DESCRIPTION**

Calling `setting()` loads a setting and returns a relavent object for later use. Here is an example of how you could load the FilesSettings setting:

<pre><code>val files = setting(name = "files", default = FilesSettings())</code></pre>

You can also change the default values like this:

<pre><code>val files = setting(name = "files", default = FilesSettings()).apply {
  //modification code
}</code></pre>

Once you have set up your server's settings, you can call `loadSettings()` to generate a `settings.yaml` file or to use an already existing one. If there is a discrepency between the `settings.yaml` file and the settings delared in code a file with suggested settings will be generated and output to the project's root directory.

## FilesSettings

**TODO: ADD DESCRIPTION**

`FilesSettings` contains the default settings for server files.

## Database

**TODO: ADD DESCRIPTION**

`DatabaseSettings` contains the default settings for a `Database`.

## JwtSigner and Server Authentication

**JSON Web Tokens (JWT) are used to authenticate http calls across the server. You use a `JwtSigner` to create and verify these tokens.**

`JwtSigner` contains the default settings for a token signer.

*Once you have set up JwtSigners for your server, you can call `authEndpoints()` to generate a few endpoints to simplify server authentication.*

### Members
- `token()` creates a token
- `verify()` validates an existing token

## EmailClient

**TODO: ADD DESCRIPTION**

`EmailSettings` contains the default settings for the server's email.

### Members
- `send()` sends an email

## FieldCollection

**`FieldCollection` is an abstract class for interacting with a database, and on a specific collection/table.**

`FieldCollection.withPermissions()` creates a `FieldCollection` with access permissions.

### Members
- `insertOne()` adds an element to the collection
- `insertMany()` adds multiple elements to the collection
<br>

- `get()` gets an element from the collection
- `getMany()` gets multiple elements from the collection
- `findOne()` gets an element from the collection
- `find()` gets multiple elements from the collection
<br>

- `updateOne()` modifies an element in the collection
- `updateMany()` modifies multiple elements in the collection
- `updateOneById()` modifies an element in the collection
<br>

- `replaceOne()` replaces an element in the collection
- `replaceOneById()` replaces an element in the collection
<br>

- `deleteOne()` deletes an element in the collection
- `deleteMany()` deletes multiple elements in the collection
- `deleteOneById()` deletes an element in the collection
<br>

- `upsertOne()` modifies an element in the collection if it exists or creates a new element if it does not
- `upsertOneById()` modifies an element in the collection if it exists or creates a new element if it does not
<br>

- `postChange()` modifies an element after an element is added to the collection
- `preDelete()` deletes an element after an element is added to the collection
- `postDelete()` deletes an element after an element is added to the collection
- `preCreate()` executes code before an element is added to the collection
- `postCreate()` executes code after an element is added to the collection

## Condition

**`Condition`s are used to test against existing data in the database.**

### Members
- `and` strings multiple conditions together

- `eq` (equal) returns true if the given values are equivalent
- `neq` (not equal) returns true if the given values are not equivalent
- `ne` (not equal) returns true if the given values are not equivalent
- `gt` (greater than) returns true if the given number is greater than another number
- `lt` (less than) returns true if the given number is less than another number
- `gte` (greater than or equal) returns true if the given number is greater than or equal to another number
- `lte` (less than or equal) returns true if the given number is less than or equal to another number
<br>

- `inside` returns true if the given value is inside a given list
- `nin` (not in) returns true if the given value is not inside a given list
- `notIn` returns true if the given value is not inside a given list
- `contains` returns true if the given value is inside a given list
<br>

- `allClear`
- `allSet`
- `anyClear`
- `anySet`
<br>

- `all` returns true if the given `condition` is true for all elements in a given list
- `any` returns true if the given `condition` is true for any element in a given list
- `sizesEquals` returns true if the given integer is equal to the size of a given list
- `containsKey` returns true if the given key is inside a given map

## Modification

**`Modification`s are used to modify existing data in a database.**

### Members
- `then` strings multiple modification calls together
<br>

- `assign` sets the given value to another value
- `coerceAtMost` restricts a given number to a given maximum
- `coerceAtLeast` restricts a given number to a given minimum
- `plus` increments a given number by another number
- `times` multiplies a given number by another number
<br>

- `plus` appends a given string to another string
- `plus` appends a given list to another list
- `addAll` is shorthand for `AppendList()`
- `addUnique` appends a given set to another set
- `removeAll` removes elements from a given list based on a `condition`
- `removeAll` removes elements from a given list that are equivalent to elements from another list
- `DropFirst()` removes the first element from a given list
- `DropLast()` removes the last element from a given list

## Models

**Models are kotlin data classes used to access and serialize data from a `database` as well as from the bodies of http calls.**

### Annotations
- `@Serializable` makes a data class serializable
- `@DatabaseModel` makes a data class interchangeable with data in the database

*Once you have set up your data classes, you can call `prepareModels()` to generate serialization code for each data class with the `@Serializable` annotation.*

### Interfaces
A data class can inherit functionality from multiple pre-defined interfaces.

- `HasId` provides an id field
- `HasEmail` provides an email address field

## Exceptions

**TODO: ADD DESCRIPTION**

- `BadRequestException()` responds with an http status code of 400
- `UnauthorizedException()` responds with an http status code of 401
- `ForbiddenException()` responds with an http status code of 403
- `NotFoundException()` responds with an http status code of 404

## Additional Convenience
- `apiHelp()` auto-generates basic documentation on the server's endpoints
