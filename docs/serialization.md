# Serialization

Lightning Server simply leverages [`kotlinx.serialization`](https://github.com/Kotlin/kotlinx.serialization) for all of your serialization needs.  It uses centralized serializer instances in the top-level `Serialization` object.

- JSON - `Serialization.json.encodeToString(MyThing(x = 1))`
- CSV - `Serialization.csv.encodeToString(MyThing(x = 1))`
- And many more!

The default serialization module contains contextual serializers for `UUID`, most `java.time.*` items, `ServerFile`, and `Optional`.

You can customize the serializer module using:

```kotlin
Serialization.module = ClientModule + serializersModuleOf(/*...*/)
```

Make sure this happens before you do any serialization in your program.

You can also customize the individual formats similarly.

If you attempt to set them more than once OR modify them after their first use, an error will occur to prevent you from strange state-based behavior.

## Shortcuts based on `Serialization`

```kotlin
// Creates a JSON response by serializing the object.
HttpResponse.json(MyThing(x = 2))

// Parses the body using the paired content type's serializer.
it.body!!.parse<MyThing>()
```

## Adding more supported content types

```kotlin
Serialization.handler(object: HttpContentHandler {
    override val contentType get() = ContentType.Application.Rss
    //...
})
```
