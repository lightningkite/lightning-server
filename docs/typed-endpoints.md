# Typed Endpoints

Lightning Server has built-in functionality to simplify creating and documenting programmatically accessed endpoints by combining:
- endpoint definitions
- authorization
- serialization

Creating an endpoint through this method will enable you to have:

- Automatically generated documentation
- Automatically generated SDKs for several languages
- Automatically generated OpenAPI information

## Defining a typed endpoint

The format of a typed endpoint looks something like this:

```kotlin
val typedSampleA = path("typed").post.typed(
    summary = "Example",
    description = "A fuller description of the endpoint",
    errorCases = listOf(LSError(
        http = 404,
        detail = "not-found",
        message = "Could not find the item."
    )),
    successCode = HttpStatus.OK,
    implementation = { user: UserType, input: InputType ->
        return@typed OutputType()
    }
)
val typedSampleB = path("typed/{id}").post.typed(
    summary = "Example",
    description = "A fuller description of the endpoint",
    errorCases = listOf(LSError(
        http = 404,
        detail = "not-found",
        message = "Could not find the item."
    )),
    successCode = HttpStatus.OK,
    implementation = { user: UserType, id: Int, input: InputType ->
        return@typed OutputType()
    }
)
```

You can use the type `Unit` to indicate that the user or input are ignored.

Here's an example:

```kotlin
val add10 = path("add10").post.typed(
    summary = "Add 10",
    description = "Adds ten to the given integer.",
    errorCases = listOf(),
    implementation = { _: Unit, value: Int ->
        return@typed value + 10
    }
)
```

## Accessing automatically generated information

Automatically generated information is part of the [meta endpoints](meta.md), follow that link to see how to set them up.