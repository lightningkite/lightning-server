# Files and Files-Shared Modules

This package provides primitives and endpoints for working with user-provided files.

- files-shared: Common models used across platforms (UploadForNextRequest, UploadInformation).
- files: JVM server endpoints and helpers for serving files and implementing the early-upload workflow.

Contents

- FileSystemEndpoints: Serve file reads and metadata over HTTP (GET/HEAD) and accept uploads (PUT) for
  KotlinxIoExternalFileSystem.
- UploadEarlyEndpoint: Opinionated flow for clients to upload files before making an API call that references them.
- helpers.kt: Utilities to convert between ServerFile and ExternalFile and small conveniences like nameWithoutExtension.

Quickstart

1) Add a files setting in your ServerBuilder:

```kotlin
val files = setting("files", ExternalFileSystem.Settings())
```

2) Include endpoints:

```kotlin
val served = path.path("files") include FileSystemEndpoints(files)
val uploadEarly = path.path("upload") include UploadEarlyEndpoint(
    files = files,
    database = database,  // a setting declared once on your ServerBuilder
    fileScanner = Runtime.Constant(listOf()),
)
```

3) Request an upload, perform PUT to uploadUrl, then use futureCallToken as a ServerFile in subsequent requests.

Notes

- GET supports Range requests (`Accept-Ranges: bytes`). HEAD returns metadata headers.
- Upload PUT requires KotlinxIoExternalFileSystem; other backends may not support server-generated upload URLs.
- ServerFile.externalFile relies on a contextual ExternalServerFileSerializer being registered in the engine's external
  serializers module.
