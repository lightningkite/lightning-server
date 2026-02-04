package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.typed.ClientModelRestEndpoints
import com.lightningkite.lightningserver.typed.LiveVersion
import com.lightningkite.services.database.AggregateQuery
import com.lightningkite.services.database.CollectionUpdates
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.GroupAggregateQuery
import com.lightningkite.services.database.GroupCountQuery
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.MassModification
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.QueryPartial

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
