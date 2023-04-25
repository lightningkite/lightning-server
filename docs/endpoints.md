# Endpoints

We've already briefly seen some very basic endpoints in action; let's go into more detail.

First, you won't get very far in this section without some knowledge of HTTP.  One tutorial you could go to for general HTTP information is [this one I found](https://dev.to/abbeyperini/a-beginners-guide-to-http-part-1-definitions-38m7) by Abbey Perini.

With that out of the way, let's start looking at how we can define endpoints.

## Routing

The typical way of defining routes is as follows:

```kotlin
object Server : ServerPathGroup(ServerPath.root) {
    //...

    // GET /
    val a = path.get.handler { /*...*/ }
    // POST /
    val b = path.post.handler { /*...*/ }
    // PATCH /test
    val c = path("test").patch.handler { /*...*/ }
    // DELETE /first/second/last
    val d = path("first/second/last").delete.handler { /*...*/ }
    // PUT /model/<insert some path segment here>/test
    val e = path("model/{id}/test").put.handler { /*...*/ }
    // GET /app/... (any number of path segments after /app/)
    val f = path("app/{...}").put.handler { /*...*/ }
}
```

To be more rigorous about it, we usually store the endpoint reference in a constant for later access.  This is useful for testing.

```kotlin
val endpointReference = TODO()
```

Then, we start from our current path (the root as defined in `Server`):

```kotlin
val endpointReference = path("path-string-here")
```

The path string can contain any number of slash separated segments.  Text and numbers are interpreted literally, while names surrounded by `{}` are interpreted as wildcard path segments.  If it ends with `/{...}`, that is interpreted as any number of arbitrary segments at the end of the path.

Paths are matched preferring exact literal matches first, then single wildcard segments, then variable wildcard segments.

Next, we pick an HTTP verb:

```kotlin
// One of:
val endpointReference = path("path-string-here").get
val endpointReference = path("path-string-here").post
val endpointReference = path("path-string-here").put
val endpointReference = path("path-string-here").patch
val endpointReference = path("path-string-here").delete
```

Finally, we define how we should respond if we call this endpoint:

```kotlin
val endpointReference = path("path-string-here").get.handler { it: HttpRequest ->
    // Calculate a response here.
    TODO()
}
```

Remember that in Kotlin, naming a single parameter to a lambda is optional.  If you don't explicitly call it out, the name will be `it`.

## Reading Request Information

We can now read the request information via the `HttpRequest` input to our handler:

```kotlin
val endpointReference = path("path-string-here").get.handler { it: HttpRequest ->
    it.endpoint  // The endpoint in question.
    it.parts  // The values of wildcard path segments.
    it.wildcard  // Any value filling {...}.
    it.queryParameter("param")  // Access to a query parameter by name (?param=value)
    it.queryParameters  // Access to the query parameters (?param=value)
    it.headers  // Access to any headers sent with the request
    it.body  // Access to the content of the request
    it.domain  // The domain used in making the request
    it.protocol  // The protocol used in making the request - HTTP or HTTPS
    it.sourceIp  // The originating public IP of the request, as can best be determined
    TODO()
}
```

Some examples:

```kotlin
// Query parameters and headers
val listItems = path("list-items").get.handler { it: HttpRequest ->
    val data = listOf(1, 2, 3, 4)
    val dataToRender = if(it.queryParameter("filter") == "odd")
        data.filter { it % 2 == 1 }
    else
        data
    val desiredContentType = it.headers.accept.firstOrNull()
    if(desiredContentType == ContentType.Application.Json) {
        TODO("Respond with JSON")
    } else {
        TODO("Respond with HTML")
    }
}
// Reading the content
val postItem = path("list-items").post.handler { it: HttpRequest ->
    val numberToAdd = it.body!!.text().toIntOrNull() ?: throw BadRequestException()
    TODO()
}
// Using path parts
val detail = path("list-items/{id}").get.handler { it: HttpRequest ->
    val index = it.parts["id"]?.toIntOrNull() ?: throw BadRequestException()
    val toShow = data[index]
    TODO()
}
```

## Responses

Next, we have to actually formulate a response.  An `HttpResponse`, to be precise.

```kotlin
val endpointReference = path("path-string-here").get.handler { 
    HttpResponse.plainText("Hello world!")
}
```

Responses have a lot of flexibility.  They are made of a body, status code, and set of headers.  You can use the `HttpResponse(body, status, headers)` constructor to manually construct a response with a fine level of detail.

There are a lot of shortcuts too.

```kotlin
HttpResponse.plainText("Some Text")
HttpResponse.redirectToGet("https://google.com")
HttpResponse.html(content = """
<!DOCTYPE html>
<html>
<body>

<h1>My First Heading</h1>
<p>My first paragraph.</p>

</body>
</html> 
""")
```

### `HttpContent`

The content can be made of text, binary content, attached to a stream, or made of multiple parts.  It is always bound to a particular content type and may know its total length.  Some common contents you might create:

```kotlin
HttpContent.Text("Some text", ContentType.Text.Plain)
HttpContent.Text("{\"json\": true}", ContentType.Application.Json)
HttpContent.Text("""
<!DOCTYPE html>
<html>
<body>

<h1>My First Heading</h1>
<p>My first paragraph.</p>

</body>
</html> 
""", ContentType.Text.Html)
HttpContent.file(File("somefile.txt"))
```

### HttpStatus

The commonly defined codes are available via `HttpStatus.something`, such as:

- `HttpStatus.OK`
- `HttpStatus.BadRequest`
- `HttpStatus.Unauthorized`
- `HttpStatus.NotFound`

You can also manually specify any code using the constructor:

```kotlin
HttpStatus(427)
```

### HttpHeaders

You can construct headers by using any of the `HttpHeaders()` constructors.  I recommend the builder one:

```kotlin
HttpHeaders {
    set("X-Example-Header", "Some Value")
    setCookie("cookie", "value")
}
```