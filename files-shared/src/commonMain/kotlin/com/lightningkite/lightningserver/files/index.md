# Package: com.lightningkite.lightningserver.files (shared)

This shared module provides simple models used by the files module and any client SDKs.

Files

- models.kt — UploadTarget and UnverifiedUpload, which clients use in the upload flow, plus the deprecated
  UploadInformation.
- ClientFileUploadEndpoints.kt — ClientFileUploadEndpoints, the client interface for the upload endpoints, and
  LiveClientFileUploadEndpoints, its Fetcher-backed implementation.

Overview

- UploadTarget is returned to clients when starting an upload; it contains the presigned PUT URL and an
  UnverifiedUpload token. Verifying that token returns a ServerFile to send in a later API call.
- The server-side tracking model, UploadForNextRequest, lives in the files module and never reaches clients.
