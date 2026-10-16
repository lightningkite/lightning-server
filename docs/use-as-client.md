# Using Lightning Server: Client Perspective

Lightning Server, while being a flexible system with which any HTTP spec could be implemented, generally tends to follow
certain patterns as it is significantly easier to build that way.

## Request MIME Types

Both the standard `Accept` and `Content-Type` headers are expected with all requests. If the endpoint you are looking at
was automatically documented, it was implemented using an abstraction that will automatically serialize and deserialize
to the documented types. A server using the basic coders supports:

- `application/json` - The default
- `application/x-www-form-urlencoded`
- `application/x-lightningserver-kotlin-bytes` - A compact binary format for Kotlin clients

Servers can register more formats, so check the server's documentation.

## Authentication

Authorization in Lightning Server is done via the standard `Authorization` header with an access token. That header
looks like this:

```http request
Authorization: Bearer <your token here>
```

Lightning Server auth works with *proofs*: each login method proves something about the user and returns a signed
proof, and the proofs are exchanged for a session. The exact paths depend on where the server mounts its endpoints, but
they typically look like this:

- `POST /proof/email/start` - Given an email address, sends the user a PIN and returns a `key`.
- `POST /proof/email/prove` - Given `{"key": "", "password": "<PIN>"}`, returns a signed proof.
- `POST /auth/login` - Given a list of proofs, returns a `refreshToken` once they are strong enough. Otherwise it lists
  the further proof `options` the user has.
- `POST /auth/token/simple` - Given the refresh token, returns a short-lived access token for the `Authorization`
  header. Call it again when the access token expires.
- `GET /auth/self` - Returns your user.

## File Uploads

A convenient feature of Lightning Server is its file upload and management. Frequently, you need to store, send, or
manipulate a file on the server.

If you want to upload a file, you'll typically follow this pattern:

- Call `GET /upload-early`, which will give you two values:
    - `uploadUrl` - a URL to `PUT` your file to.
    - `futureCallToken` - a psuedo-URL that represents the uploaded file and permission to use it in another request. It
      does not have read access to prevent server abuse, but you can use it in subsequent requests as a `ServerFile`.
- Upload the file via the `uploadUrl` given previously
- Make your goal call with the stand-in value `futureCallToken` given previously

## REST Endpoints

Lightning Server has a feature that allows a developer to create REST endpoints based on just permissions, a model, and
a database.

The generated endpoints frequently talk in terms of `Condition<T>` and `Modification<T>`, which are extremely flexible
definitions of filters and change requests respectively.

### Conditions

Conditions are always an object with a single key and value, where the value type depends on the key.

Options:

- `{ "Never": true }`
    - Never let anything through. You probably won't use this.
- `{ "Always": true }`
    - Let everything through.
- `{ "And": [<subconditions>] }`
    - Only let items through that fulfill every condition in the array.
- `{ "Or": [<subconditions>] }`
    - Only let items through that fulfill at least one condition in the array.
- `{ "<field name>": <condition on field's type> }`
    - Run a condition on a particular field.
- `{ "Equal": <value> }`
    - Only let through items that match the given value exactly.
- `{ "NotEqual": <value> }`
    - Only let through items that do not match the given value exactly.
- `{ "Inside": [<values>] }`
    - Only let through items that match one of the given values exactly.
- `{ "NotInside": [<values>] }`
    - Only let through items that do not match one of the given values exactly.
- `{ "GreaterThan": <value> }`
- `{ "LessThan": <value> }`
- `{ "GreaterThanOrEqual": <value> }`
- `{ "LessThanOrEqual": <value> }`
    - Comparison filters. Only operate on comparable values, like numbers and strings.
- `{ "StringContains": { "value": <value>, "ignoreCase": true } }`
    - Only let through values that contain the given string. Only operates on strings.
- `{ "RegexMatches": { "value": <regular expression>, "ignoreCase": true } }`
    - Only let through values that match the regular expression. Only operates on strings.
- `{ "ListAllElements": <condition for element type> }`
    - Only match lists where every element matches the given condition.
- `{ "ListAnyElements": <condition for element type> }`
    - Only match lists where some element matches the given condition.
- `{ "ListSizesEquals": <count> }`
    - Only matches lists that contain exactly `count` elements.
- `{ "SetAllElements": <condition for element type> }`
    - Only match sets where every element matches the given condition.
- `{ "SetAnyElements": <condition for element type> }`
    - Only match sets where some element matches the given condition.
- `{ "SetSizesEquals": <count> }`
    - Only matches sets that contain exactly `count` elements.
- `{ "IfNotNull": <condition on non-null type> }`
    - Allows you to run conditions on a nullable field

Sample reads as "name is equal to Test and number is either 1, 2, or 3.":

```json
{
  "And": [
    {
      "name": {
        "Equal": "Test"
      }
    },
    {
      "number": {
        "Inside": [1, 2, 3]
      }
    },
    {
      "listOfInts": {
        "ListAnyElements": {
          "Or": [{ "GreaterThan": 8 }, { "LessThan": 2 }]
        }
      }
    },
    { "email": {  "StringContains": { "value": "@domain.com", "ignoreCase": true } } }
  ]
}
```

### Updating items

Patch a modification to `PATCH modelName/rest/<id>` to modify an item by ID. See how modifications work below.

### Modifications

Modifications follow a similar format to conditions:

- `{ "Assign": <value> }`
    - Directly assign a value.
- `{ "<field name>": <submodification> }`
    - Run a modification on a particular field.
- `{ "Chain": [<submodifications>] }`
    - Perform multiple modifications in order
- `{ "IfNotNull": <value> }`
    - Only apply a modification if the current value isn't null.
- `{ "CoerceAtMost": <value> }`
    - Forces the value to be, at most, `value`. Also known as `min` in many languages
- `{ "CoerceAtLeast": <value> }`
    - Forces the value to be, at least, `value`. Also known as `max` in many languages
- `{ "Increment": <value> }`
    - Increments numerical values by the given `value`.
- `{ "Multiply": <value> }`
    - Multiplies numerical values by the given `value`.
- `{ "AppendString": <value> }`
    - Appends a string to the existing value.
- `{ "ListAppend": [<values>] }`
    - Adds all of the given values.
- `{ "ListRemove": <condition> }`
    - Removes all values matching the condition.
- `{ "ListRemoveInstances": [<values>] }`
    - Removes matching `values` from the list.
- `{ "ListDropFirst": true }`
    - Removes the first item in the list.
- `{ "ListDropLast": true }`
    - Removes the last item in the list.
- `{ "ListPerElement": { "condition": <subcondition>, "modification": <submodification> } }`
    - On each element, applies the modification if the condition passes.
- `{ "SetAppend": [<values>] }`
    - Adds the given values.
- `{ "SetRemove": <condition> }`
    - Removes all values matching the condition.
- `{ "SetRemoveInstances": [<values>] }`
    - Removes matching `values` from the set.
- `{ "SetPerElement": <value> }`
    - On each element, applies the modification if the condition passes.
- `{ "Combine": {"key": "value", "key2": "value2"} }`
    - Merges the existing dictionary with the given dictionary. Will overwrite existing keys.
- `{ "ModifyByKey": { "key": <submodification> } }`
    - Applies modifications to a dictionary on a per-key basis.
- `{ "RemoveKeys": [<keys>] }`
    - Removes the keys from a dictionary.

Sample reads as

- Set first name to "Test"
- Set last name to "Name"
- Increment age by one

```json
{
  "Chain": [
    {
      "firstName": {
        "Assign": "Test"
      }
    },
    {
      "lastName": {
        "Assign": " Name"
      }
    },
    {
      "age": {
        "Increment": 1
      }
    }
  ]
}
```
