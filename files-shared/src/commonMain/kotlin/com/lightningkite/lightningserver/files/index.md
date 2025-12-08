# Package: com.lightningkite.lightningserver.files (shared)

This shared module provides simple models used by the files module and any client SDKs.

Files
- models.kt — UploadForNextRequest and UploadInformation used to power the early-upload flow.

Overview
- UploadForNextRequest is stored server-side to track single-use prepared uploads and cleanup.
- UploadInformation is returned to clients when initiating an early upload; it contains the presigned PUT URL and a token that can be sent later as a ServerFile in an API call.
