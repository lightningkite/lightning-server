package com.lightningkite.lightningserver.guide.samples

// region aws-server-imports
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.plainText
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
// endregion aws-server-imports

// region aws-server-definition
// The server definition is engine-agnostic: the same object is used for local
// Ktor/Netty development and for the Lambda runtime via AwsAdapter.
object ApiServer : ServerBuilder() {

    // Each setting declares a service the server requires.
    // The deployment's settings() override resolves each one to a concrete
    // cloud resource — a DynamoDB table, an S3 bucket, a MongoDB cluster, etc.
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val root = path.get bind HttpHandler {
        HttpResponse.plainText("OK")
    }
}
// endregion aws-server-definition
