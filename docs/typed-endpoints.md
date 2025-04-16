# Typed Endpoints

Last updated April 16, 2025 (`version-4`)

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
val typedSampleA = path("typed").post.api(
    summary = "Example",
    description = "A fuller description of the endpoint",
    authOptions = noAuth,
    errorCases = listOf(LSError(
        http = 404,
        detail = "not-found",
        message = "Could not find the item."
    )),
    successCode = HttpStatus.OK,
    implementation = { input: InputType ->
        return@typed OutputType()
    }
)
val typedSampleB = path("typed").arg("arg", String.serializer).post.typed(
    summary = "Example",
    description = "A fuller description of the endpoint",
    authOptions = authOptions<User>(),
    errorCases = listOf(LSError(
        http = 404,
        detail = "not-found",
        message = "Could not find the item."
    )),
    successCode = HttpStatus.OK,
    implementation = { input: InputType ->
        println("arg is ${path1}")
        return@typed OutputType()
    }
)
```

You can use the type `Unit` to indicate that the user or input are ignored.

Here's an example:

```kotlin
val add10 = path("add10").post.api(
    summary = "Add 10",
    description = "Adds ten to the given integer.",
    authOptions = noAuth,
    errorCases = listOf(),
    implementation = { value: Int ->
        return@api value + 10
    }
)
```

## Accessing automatically generated information

Automatically generated information is part of the [meta endpoints](meta.md), follow that link to see how to set them up.