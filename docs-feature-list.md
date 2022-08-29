# LightningServer feature list

LightningServer is chock-full of useful features. This list is designed to be used as a quick reference for those
already experienced with the library.

## General

- `prepareModels()`
- `runServer()`

### PubSubInterface

- `LocalPubSub`

### CacheInterface

- `LocalCache`

### Annotations

- `@file:UseContextualSerialization()` - enables contextual serialization for the file
- `@Serializable` - makes a data class serializable
- `@DatabaseModel` - makes a data class a database model

### Model Interfaces

- `HasId` - provides a data class with an id
- `HasEmail` - provides a data class with an email address

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

- `path(): ServerPath` - creates a `ServerPath`
- `ServerPath::get: HttpEndpoint` - creates a `get` at the path of the `ServerPath`
- `ServerPath::post: HttpEndpoint` - creates a `post` at the path of the `ServerPath`
- `ServerPath::put: HttpEndpoint` - creates a `put` at the path of the `ServerPath`
- `ServerPath::patch: HttpEndpoint` - creates a `patch` at the path of the `ServerPath`
- `ServerPath::delete: HttpEndpoint` - creates a `delete` at the path of the `ServerPath`
- `ServerPath::head: HttpEndpoint` - creates a `head` at the path of the `ServerPath`
- `ServerPath::authEndpoints()`
- `ServerPath::apiHelp()` - generates documentation for every typed [HttpEndpoint](#httpendpoint)

## HttpEndpoint

- `HttpEndpoint::handler(): HttpEndpoint` - creates a handler for the `HttpEndpoint`
- `HttpEndpoint::typed(): ApiEndpoint` - creates a typed handler for the `HttpEndpoint`

## ServerPathGroup

- `ServerPathGroup::path(): ServerPath` - creates a `ServerPath` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::get: HttpEndpoint` - creates a `get` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::post: HttpEndpoint` - creates a `post` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::put: HttpEndpoint` - creates a `put` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::patch: HttpEndpoint` - creates a `patch` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::delete: HttpEndpoint` - creates a `delete` appended to the path of the `ServerPathGroup`
- `ServerPathGroup::head: HttpEndpoint` - creates a `head` appended to the path of the `ServerPathGroup`

## ModelInfoWithDefault

- `ModelInfoWithDefault::serialization` - stores `ModelSerializationInfo` for the model
- `ModelInfoWithDefault::collection()` - gets the `FieldCollection` associated with the model
- `ModelInfoWithDefault::defaultItem()` - creates the default model for the `FieldCollection`

## Database

- `Database::collection()` - returns a [FieldCollection](#fieldcollection) of a certain type

## FieldCollection

- `FieldCollection::withPermissions()` - adds permissions to the `FieldCollection`
- `FieldCollection::insertOne()` - adds an element to the `FieldCollection`
- `FieldCollection::insertMany()` - adds multiple elements to the `FieldCollection`
- `FieldCollection::get()` - gets an element from the `FieldCollection`
- `FieldCollection::getMany()` - gets multiple elements from the `FieldCollection`
- `FieldCollection::findOne()` - gets an element from the `FieldCollection`
- `FieldCollection::find()` - gets multiple elements from the `FieldCollection`
- `FieldCollection::updateOne()` - modifies an element in the `FieldCollection`
- `FieldCollection::updateMany()` - modifies multiple elements in the `FieldCollection`
- `FieldCollection::updateOneById()` - modifies an element in the `FieldCollection`
- `FieldCollection::replaceOne()` - replaces an element in the `FieldCollection`
- `FieldCollection::replaceOneById()` - replaces an element in the `FieldCollection`
- `FieldCollection::deleteOne()` - deletes an element in the `FieldCollection`
- `FieldCollection::deleteMany()` - deletes multiple elements in the `FieldCollection`
- `FieldCollection::deleteOneById()` - deletes an element in the `FieldCollection`
- `FieldCollection::upsertOne()` - upserts an element in/to the `FieldCollection`
- `FieldCollection::upsertOneById()` - upserts an element in/to the `FieldCollection`
- `FieldCollection::postChange()` - modifies an element after an element is added to the `FieldCollection`
- `FieldCollection::preDelete()` - deletes an element after an element is added to the `FieldCollection`
- `FieldCollection::postDelete()` - deletes an element after an element is added to the `FieldCollection`
- `FieldCollection::preCreate()` - executes code before an element is added to the `FieldCollection`
- `FieldCollection::postCreate()` - executes code after an element is added to the `FieldCollection`

# Condition

- `condition(): Condition` - creates a `Condition`
- `Condition::Always()` - returns true
- `Condition::Never()` - returns false
- `and` - returns true if both of the given `Conditions` are true
- `or` - returns true if either of the given `Conditions` are true
- `not` - returns true if the given `Condition` is not true
- `eq` - **(equal)** returns true if the given values are equivalent
- `neq` - **(not equal)** returns true if the given values are not equivalent
- `ne` - equivalent to `neq`
- `gt` - **(greater than)** returns true if the given number is greater than another number
- `lt` - **(less than)** returns true if the given number is less than another number
- `gte` - **(greater than or equal)** returns true if the given number is greater than or equal to another number
- `lte` - **(less than or equal)** returns true if the given number is less than or equal to another number
- `inside` - returns true if the given value is inside a given list
- `notIn` - returns true if the given value is not inside a given list
- `nin` - equivalent to `notIn`
- `contains` - returns true if the given value is inside a given list
- `allClear` - returns true if all the given bits in a bitmask that correspond to given set (1) bits are clear (0)
- `allSet` - returns true if all the given bits in a bitmask that correspond to given set (1) bits are set (1)
- `anyClear` - returns true if any of the given bits in a bitmask that correspond to given set (1) bits are clear (0)
- `anySet` - returns true if any of the given bits in a bitmask that correspond to given set (1) bits are set (1)
- `all` - returns true if the given `Condition` is true for all elements in a given list
- `any` - returns true if the given `Condition` is true for any element in a given list
- `sizesEquals` - returns true if the given integer is equal to the size of a given list
- `containsKey` - returns true if the given key is inside a given map

# Modification

- `modification(): Modification` - creates a `Modification`
- `then` - strings multiple `Modifications` together
- `assign` - sets the given value to another value
- `coerceAtMost` - restricts a given number to a given maximum
- `coerceAtLeast` - restricts a given number to a given minimum
- `plus` - increments a given number by another number
- `times` - multiplies a given number by another number
- `plus` - appends a given string to another string
- `plus` - appends a given list to another list
- `addAll` - appends a given list to another list
- `addUnique` - appends a given set to another set
- `removeAll` - removes elements from a given list based on a `Condition`
- `removeAll` - removes elements from a given list that are equivalent to elements from another list
- `dropFirst()` - removes the first element from a given list
- `dropLast()` - removes the last element from a given list

# Documentable

- `Documentable::kotlinSdk()` - generates an SDK for the server in a .zip file
- `Documentable::kotlinSdkLocal()` - generates an SDK for the server in a local file
- `Documentable::kotlinApi()`
- `Documentable::kotlinLiveApi()`
- `Documentable::kotlinSessions()`
- `Documentable::docGroup`
- `Documentable::functionName`