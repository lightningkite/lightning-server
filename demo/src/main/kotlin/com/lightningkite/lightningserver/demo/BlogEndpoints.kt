package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.models.BlogPost
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.ModelPermissions

object BlogEndpoints : ServerBuilder() {
    // The correct way of doing it
    val info = Server.database.modelInfo(
        auth = Server.UserAuth.require(),
        tableName = "BlogPost",
        permissions = {
            if (auth.fetch().isSuperUser)
                ModelPermissions.allowAll<BlogPost>()
            else
                ModelPermissions(read = Condition.Always, manage = Condition.Never)
        },
    )
    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
}


//object CommentEndpoints: ServerBuilder() {
//    // The correct way of doing it
//    val info = Server.database.modelInfo(
//        auth = Server.UserAuth.require(),
//        permissions = {
//            // TODO: Real permissions
//            ModelPermissions.allowAll<BlogPost>()
//        },
//    )
//    val rest = path.path("rest") include ModelRestEndpoints(info) + ModelRestUpdatesWebsocket(info)
//}
