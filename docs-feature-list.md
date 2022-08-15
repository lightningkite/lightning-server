# LightningServer feature list

LightningServer is chock-full of useful features. This list is designed to be used as a quick reference for those
already experienced with the library.

## General

- `prepareModels()`
- `runServer()`
- `routing()`
- `path()`

### PubSubInterface

- `LocalPubSub`

### CacheInterface

- `LocalCache`

### Annotations

- `@file:UseContextualSerialization` enables contextual serialization for the file
- `@Serializable` makes a data class serializable
- `@DatabaseModel` - makes a data class a database model

### Model Interfaces

- `HasId` provides a data class with an id
- `HasEmail` provides a data class with an email address

### Exceptions

Throwing these exceptions in the body of an [HttpEndpoint](#httpendpoint) will automatically return the corresponding
HTTP response.

- `BadRequestException()` - 400
- `UnauthorizedException()` - 401
- `ForbiddenException()` - 403
- `NotFoundException()` - 404

### Settings

- `setting()`
- `loadSettings()`
- `GeneralServerSettings`
- `FilesSettings`
- `DatabaseSettings`
- `JwtSigner`
- `EmailSettings`
- `generalSettings`
- `parsingFileSettings`

## ServerPath

- `ServerPath::get`
- `ServerPath::post`
- `ServerPath::put`
- `ServerPath::patch`
- `ServerPath::delete`
- `ServerPath::head`
- `ServerPath::autoCollection()` - generates a restful api for a [FieldCollection](#fieldcollection)
- `ServerPath::authEndpoints()`
- `ServerPath::apiHelp()` - generates documentation for every typed [HttpEndpoint](#httpendpoint)

## HttpEndpoint

- `HttpEndpoint::handler()`
- `HttpEndpoint::typed()`

## Database

- `Database::collection()` - returns a [FieldCollection](#fieldcollection) of a certain type

## FieldCollection

- `FieldCollection::withPermissions()` - adds permissions to the FieldCollection
- `FieldCollection::insertOne()` adds an element to the collection
- `FieldCollection::insertMany()` adds multiple elements to the collection
- `FieldCollection::get()` gets an element from the collection
- `FieldCollection::getMany()` gets multiple elements from the collection
- `FieldCollection::findOne()` gets an element from the collection
- `FieldCollection::find()` gets multiple elements from the collection
- `FieldCollection::updateOne()` modifies an element in the collection
- `FieldCollection::updateMany()` modifies multiple elements in the collection
- `FieldCollection::updateOneById()` modifies an element in the collection
- `FieldCollection::replaceOne()` replaces an element in the collection
- `FieldCollection::replaceOneById()` replaces an element in the collection
- `FieldCollection::deleteOne()` deletes an element in the collection
- `FieldCollection::deleteMany()` deletes multiple elements in the collection
- `FieldCollection::deleteOneById()` deletes an element in the collection
- `FieldCollection::upsertOne()` modifies an element in the collection if it exists or creates a new element if it does not
- `FieldCollection::upsertOneById()` modifies an element in the collection if it exists or creates a new element if it does not
- `FieldCollection::postChange()` modifies an element after an element is added to the collection
- `FieldCollection::preDelete()` deletes an element after an element is added to the collection
- `FieldCollection::postDelete()` deletes an element after an element is added to the collection
- `FieldCollection::preCreate()` executes code before an element is added to the collection
- `FieldCollection::postCreate()` executes code after an element is added to the collection

# Condition

- `Condition::Always()` - returns true
- `Condition::Never()` - returns false
- `condition()` - creates a Condition
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
- `allClear` returns true if all the given bits in a bitmask that correspond to the given set (1) bits in another bitmask are clear (0)
- `allSet` returns true if all the given bits in a bitmask that correspond to the given set (1) bits in another bitmask are set (1)
- `anyClear` returns true if any of the given bits in a bitmask that correspond to the given set (1) bits in another bitmask are clear (0)
- `anySet` returns true if any of the given bits in a bitmask that correspond to the given set (1) bits in another bitmask are set (1)
- `all` returns true if the given `condition` is true for all elements in a given list
- `any` returns true if the given `condition` is true for any element in a given list
- `sizesEquals` returns true if the given integer is equal to the size of a given list
- `containsKey` returns true if the given key is inside a given map

# Modification

- `modification()` creates a Modification
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

# Documentable

- `Documentable::kotlinSdkLocal()` - generates an sdk for the server