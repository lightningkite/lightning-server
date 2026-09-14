package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.typed.LiveVersion

@LiveVersion(LiveClientUploadEarlyEndpoints::class)
public interface ClientUploadEarlyEndpoints {
    /**
     * Upload File for Request
     *
     * Upload a file to make a request later.  Times out in around 10 minutes.
     *
     * **Auth Requirements:** No Requirements
     * */
    public suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation

    /**
     * Verify uploaded file
     *
     * Checks out a file and moves it out of jail if it's safe.  Makes for significantly faster subsequent requests.
     *
     * **Auth Requirements:** No Requirements
     * */
    public suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String
}
