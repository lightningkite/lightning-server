# Package: com.lightningkite.lightningserver.files

This package contains the HTTP endpoints and utilities for working with PublicFileSystem-backed files.

Files
- FileSystemEndpoints.kt — GET/HEAD for reading files and metadata; PUT for uploads supported by KotlinxIoPublicFileSystem.
- UploadEarlyEndpoints.kt — Endpoints implementing the early-upload flow (request upload URL, optional verify, scheduled cleanup).
- helpers.kt — Utilities to convert between ServerFile and FileObject and small convenience extensions.

Overview
- FileSystemEndpoints lets you map a path group (e.g., /files/**) to a PublicFileSystem so clients can fetch content via presigned URLs and (when supported) upload via presigned PUT.
- UploadEarlyEndpoint provides a safe pattern for uploading a file before using it in an API call. It stores a single-use record in the database and integrates with optional FileScanner implementations.

Notes
- Range requests on GET are not yet supported and will be rejected; HEAD returns metadata headers.
- ServerFile.fileObject requires that a contextual ExternalServerFileSerializer is registered in the current ServerRuntime's serialization module.
