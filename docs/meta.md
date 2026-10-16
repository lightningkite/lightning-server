# Meta Tools

Lightning Server comes with a bunch of easy tools for working with your server.

You can enable them just like this:

```kotlin
import com.lightningkite.lightningserver.typed.MetaEndpoints

object Server : ServerBuilder() {
    val meta = path.path("meta") module MetaEndpoints(
        packageName = "com.yourcompany.yourproject",
        database = database,
        cache = cache
    )
}
```

Now, if you visit your server's `/meta` page, you'll have access to several convenient tools.

## /meta/docs

Some plain text documentation for all of your typed endpoints and their associated types.

Also contains a link to get SDKs for several programming languages.

## /meta/health

A JSON-returning endpoint that will report the health status of your system, including memory usage, load, and health
statuses for each individual dependent system such as caches, database, files, and emails.

## /meta/online

Simply returns the plain-text 200 response "Server is running."  Can be useful for quick checks.

## /meta/admin

An administrative panel that allows for management of any models you've exposed RESTful endpoints for, as well as
testers for your endpoints.

## /meta/openapi

Automatically-generated Swagger docs.

## /meta/openapi.json

Automatically-generated OpenAPI JSON spec.

## /meta/kschema

Returns a Lightning Server specific schema in JSON, used for SDK generation.

## /meta/paths

Returns a list of all paths that the server responds to, as well as all tasks, schedules, and more.

## /meta/ws-tester

A convenient web socket tester.

## /meta/bulk

Runs several typed endpoint calls in one request and returns their results keyed the same way. Each call is a
sub-request: it runs through the server's interceptors as a child execution of the bulk request, and may carry its own
`Authorization`.
