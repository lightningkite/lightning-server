# File Systems

Storing, serving, and using user-provided files is a common requirement, and as such, is built-in directly.

Valid file backends that have been built so far are Local, S3, and Azure Blob Storage.  SFTP is also partially supported - it doesn't support public URLs.

The API is roughly based on Kotlin's built-in file functions.

## Declaring the need for a file sysstem

Add a setting as follows:

```kotlin
object Server {
    //...
    val files = setting(name = "files", default = FilesSettings())
    //...
}
```

## Accessing files

```kotlin
val rootFolder = Server.files().root

val testFile = rootFolder.resolve("some/path/file.txt")

// Files are written in terms of `HttpContent`
// There's no mkdir.  If a folder does not exist it will be created.
testFile.put(HttpContent.Text("Hello world!", ContentType.Text.Plain))

// Files are read in terms of `HttpContent` as well
testFile.get()!!.stream().use { it: InputStream ->
    it.readAllBytes()
}

// You can just get metadata too
testFile.head()

// Generates a file reference with a large random identifier in it.
val newFile = rootFolder.resolveRandom("test", "txt")

// You can list files too.
rootFolder.list().forEach {
    println(it)
}
```

## Serving files

These files have URLs that are signed for retrieval.  Performing an HTTP GET will result in the file.

```kotlin
// Duration of the signature is determined by the file system settings
println(testFile.signedUrl)
```

## Uploading files from a client

You can sign an upload URL like so:

```kotlin
// Include the expiration duration
alt.uploadUrl(Duration.ofMinutes(10))
```

Performing an HTTP PUT with the file's contents will overwrite that file.

## Serialization

`FileObject` is a file-system resolve object which can be used to read and write files.  They are purely internal to the server.

`ServerFile` is a wrapper around a string that contains a public URL for an object.  They are used in APIs and serialization.

You can switch between the two using `FileObject.serverFile` and `ServerFile.fileObject`.  

### Security

When a `ServerFile` is sent to a client, the url is automatically signed for reading.  Therefore, if you wish to keep a file in your file system secure, only serialize references to it for the people you want to read it.

## Default File Upload Endpoints

There is a pre-built upload endpoint for uploading files to use in subsequent requests.  It requires a reference to the intended file system to use, a database to track whether the file has been used (if it's unused, it is garbage collected), and a `JwtSigner` setting to secure file reuse.

This endpoint prevents abuse of your file system by returning two URLs: a `uploadUrl` and a `futureCallToken`.

`uploadUrl` is the URL which the client should PUT their file to.

`futureCallToken` is a URL that can be used as a `ServerFile` in a subsequent request.

Neither URL will allow reading of the file, and thus, you cannot abuse this endpoint as a file-sharing system.

It is *strongly* recommended that you use this endpoint for handling files in your API rather than attempting to implement it yourself.

```kotlin
val upload = UploadEarlyEndpoint(path("early-upload"), files, database, signer)
```

## Available Backends

### Local

Simply use a local filesystem folder.

```json5
// settings.json
{
  "files": { "url": "file://path-to-folder" }
}
```

### S3

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds S3FileSystem to the possible file system loaders
    init { S3FileSystem }
}
```

```json5
// settings.json
{
  "files": { "url": "s3://[user]:[password]@[bucket].[region].amazonaws.com" }
}
```

### Azure Blob Storage

```kotlin
// Server.kt
object Server: ServerPathGroup(ServerPath.root) {
    // Adds AzureFileSystem to the possible file system loaders
    init { AzureFileSystem }
}
```

```json5
// settings.json
{
  "files": { "url": "azbs://key@account/container" }
}
```