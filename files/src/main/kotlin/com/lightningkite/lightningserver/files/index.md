# Package: com.lightningkite.lightningserver.files

This package contains the HTTP endpoints and utilities for working with ExternalFileSystem-backed files.

Files

- FileSystemEndpoints.kt — GET/HEAD for reading files and metadata; PUT for uploads supported by
  KotlinxIoExternalFileSystem.
- UploadEarlyEndpoints.kt — Endpoints implementing the early-upload flow (request upload URL, optional verify, scheduled
  cleanup).
- helpers.kt — Utilities to convert between ServerFile and ExternalFile and small convenience extensions.
- UploadToken.kt — The signed token that identifies a prepared upload.
- ranging.kt — Parsing of `Range` headers and slicing of file content to satisfy them.
- validation.kt — `AnnotationValidators.Files`, validators for ServerFile annotations.

Overview

- FileSystemEndpoints lets you map a path group (e.g., /files/**) to a ExternalFileSystem so clients can fetch content via
  presigned URLs and (when supported) upload via presigned PUT.
- UploadEarlyEndpoint provides a safe pattern for uploading a file before using it in an API call. It stores a
  single-use record in the database and integrates with optional FileScanner implementations.

Notes

- GET supports `Range` requests (`Accept-Ranges: bytes`); how malformed ranges are treated is configurable. HEAD
  returns metadata headers.
- ServerFile.fileObject requires that a contextual ExternalServerFileSerializer is registered in the current
  ServerRuntime's serialization module.
