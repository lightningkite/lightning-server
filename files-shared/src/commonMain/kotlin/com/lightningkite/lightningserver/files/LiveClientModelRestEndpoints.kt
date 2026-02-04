package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.typed.Fetcher
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
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.Query
import com.lightningkite.services.database.QueryPartial
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

public open class LiveClientUploadEarlyEndpoints(
    public val fetcher: Fetcher,
    public val subpath: String,
): ClientUploadEarlyEndpoints {
    override suspend fun uploadFileForRequest(): com.lightningkite.lightningserver.files.UploadInformation =
        fetcher(subpath, HttpMethod.GET, kotlin.Unit.serializer(), kotlin.Unit, com.lightningkite.lightningserver.files.UploadInformation.serializer())
    override suspend fun verifyUploadedFile(input: kotlin.String): kotlin.String =
        fetcher("$subpath/verify", HttpMethod.POST, kotlin.String.serializer(), input, kotlin.String.serializer())
}
