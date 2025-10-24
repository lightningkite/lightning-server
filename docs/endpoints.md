# Endpoints

Last updated January 2025 (`version-5`)

We've already briefly seen some very basic endpoints in action; let's go into more detail.

First, you won't get very far in this section without some knowledge of HTTP.  One tutorial you could go to for general HTTP information is [this one I found](https://dev.to/abbeyperini/a-beginners-guide-to-http-part-1-definitions-38m7) by Abbey Perini.

With that out of the way, let's start looking at how we can define endpoints.

## Routing

The typical way of defining routes is as follows:

```kotlin
object Server : ServerBuilder() {
    //...

    // GET /
    val a = path.get bind HttpHandler { /*...*/ }
    // POST /
    val b = path.post bind HttpHandler { /*...*/ }
    // PATCH /test
    val c = path.path("test").patch bind HttpHandler { /*...*/ }
    // DELETE /first/second/last
    val d = path.path("first").path("second").path("last").delete bind HttpHandler { /*...*/ }
    // PUT /model/<insert some path segment here>/test
    val e = path.path("model").arg<String>("id").path("test").put bind HttpHandler { /*...*/ }
}
```

To be more rigorous about it, we usually store the endpoint reference in a constant for later access.  This is useful for testing and for calling endpoints internally.

```kotlin
val endpointReference = path.path("path-string-here").get bind HttpHandler {
    // implementation
}
```

Then, we start from our current path (the root as defined in `Server`):

```kotlin
path.path("some-path") // adds a constant path segment
```

The path string should contain a single segment name. To add multiple segments, chain `.path()` calls:

```kotlin
path.path("users").path("profile")  // /users/profile
```

For wildcard path segments, use `.arg<T>("name")`:

```kotlin
path.path("users").arg<String>("id")  // /users/{id}
```

Paths are matched preferring exact literal matches first, then typed wildcard segments.

Next, we pick an HTTP verb:

```kotlin
// One of:
val endpointReference = path.path("path-string-here").get
val endpointReference = path.path("path-string-here").post
val endpointReference = path.path("path-string-here").put
val endpointReference = path.path("path-string-here").patch
val endpointReference = path.path("path-string-here").delete
```

Finally, we bind a handler to respond to this endpoint:

```kotlin
val endpointReference = path.path("path-string-here").get bind HttpHandler { request: HttpRequest ->
    // Calculate a response here.
    HttpResponse.plainText("Response!")
}
```

Remember that in Kotlin, naming a single parameter to a lambda is optional.  If you don't explicitly call it out, the name will be `it`.

## Reading Request Information

We can now read the request information via the `HttpRequest` input to our handler:

```kotlin
val endpointReference = path.path("path-string-here").get bind HttpHandler { request ->
    request.endpoint  // The endpoint path specification
    request.path      // The resolved path with argument values
    request.queryParameter("param")  // Access to a query parameter by name (?param=value)
    request.queryParameters  // Access to all query parameters
    request.headers  // Access to any headers sent with the request
    request.body  // Access to the content of the request
    request.domain  // The domain used in making the request
    request.protocol  // The protocol used in making the request - HTTP or HTTPS
    request.sourceIp  // The originating public IP of the request
    // Calculate response...
}
```

Some examples:

```kotlin
// Query parameters and headers
val listItems = path.path("list-items").get bind HttpHandler { request ->
    val data = listOf(1, 2, 3, 4)
    val dataToRender = if(request.queryParameter("filter") == "odd")
        data.filter { it % 2 == 1 }
    else
        data
    HttpResponse.json(dataToRender)
}

// Reading the body content
val postItem = path.path("list-items").post bind HttpHandler { request ->
    val numberToAdd = request.body!!.text().toIntOrNull()
        ?: throw BadRequestException("Invalid number")
    // Process the number...
    HttpResponse.plainText("Added $numberToAdd")
}

// Using path arguments
val detail = path.path("list-items").arg<Int>("id").get bind HttpHandler { request ->
    val id = request.path.arg1  // Type-safe access to first argument
    // Fetch and return item by id...
    HttpResponse.plainText("Item $id")
}

// Multiple path arguments
val userPost = path.path("users").arg<String>("userId").path("posts").arg<Int>("postId").get bind HttpHandler { request ->
    val userId = request.path.arg1  // First argument (String)
    val postId = request.path.arg2  // Second argument (Int)
    HttpResponse.plainText("User $userId, Post $postId")
}
```

## Responses

Next, we have to actually formulate a response.  An `HttpResponse`, to be precise.

```kotlin
val endpointReference = path.path("path-string-here").get bind HttpHandler {
    HttpResponse.plainText("Hello world!")
}
```

Responses have a lot of flexibility.  They are made of a body, status code, and set of headers.  You can use the `HttpResponse(body, status, headers)` constructor to manually construct a response with a fine level of detail.

There are a lot of shortcuts too:

```kotlin
HttpResponse.plainText("Some Text")
HttpResponse.redirectToGet("https://google.com")
HttpResponse.html {
    head {
        title { +"My Page" }
    }
    body {
        h1 { +"My First Heading" }
        p { +"My first paragraph." }
    }
}
```

### `TypedData`

The content is represented as `TypedData`, which includes both the data and its media type. Common ways to create content:

```kotlin
import com.lightningkite.services.data.TypedData

TypedData.text("Some text", "text/plain")
TypedData.json("{\"json\": true}")
HttpResponse(body = TypedData.bytes(byteArray, "application/octet-stream"))
```

### HttpStatus

The commonly defined codes are available via `HttpStatus.something`, such as:

- `HttpStatus.OK`
- `HttpStatus.BadRequest`
- `HttpStatus.Unauthorized`
- `HttpStatus.NotFound`
- `HttpStatus.InternalServerError`

You can also manually specify any code using the constructor:

```kotlin
HttpStatus(427)
```

### HttpHeaders

You can construct headers using the `HttpHeaders()` constructor with a builder:

```kotlin
HttpHeaders {
    set("X-Example-Header", "Some Value")
    setCookie("cookie", "value")
}
```

## Defining Groups of Endpoints

The recommended format for defining groups of endpoints is this:

```kotlin
object Server : ServerBuilder() {
    val api = path.path("api") include ApiEndpoints
}

object ApiEndpoints : ServerBuilder() {
    // GET /api/example
    val endpoint = path.path("example").get bind HttpHandler {
        HttpResponse.plainText("example")
    }
}
```

This format allows you to group and separate your endpoints effectively while still making routing centralized and clear, as well as ensuring testing is still easy.

## Handling HTML

While Lightning Server is mostly focused on creating API backends, you can also serve HTML out of it using Kotlin's HTML DSL:

```kotlin
val homepage = path.get bind HttpHandler {
    HttpResponse.html {
        head {
            meta(charset = "utf-8")
            title { +"My Application" }
        }
        body {
            h1 { +"Welcome!" }
            p { +"This is my Lightning Server application." }
        }
    }
}
```

For serving static files, you'll want to use the file system abstraction (see [Files documentation](files.md)).
