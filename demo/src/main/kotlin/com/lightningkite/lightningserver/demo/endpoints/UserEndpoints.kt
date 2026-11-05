package com.lightningkite.lightningserver.demo.endpoints

import com.lightningkite.lightningserver.auth.AuthRequirement
import com.lightningkite.lightningserver.auth.fetch
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.auth.require
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.demo.Server.database
import com.lightningkite.lightningserver.demo.models.*
import com.lightningkite.lightningserver.demo.UserAuth
import com.lightningkite.lightningserver.typed.ModelInfo
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.ModelPermissions
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eqNn
import com.lightningkite.services.database.mask
import com.lightningkite.services.database.or
import kotlin.uuid.Uuid

object UserEndpoints : ServerBuilder() {

    val info: ModelInfo<User?, User, Uuid> = database.modelInfo(
        auth = UserAuth.require() or AuthRequirement.None,
        tableName = "User",
        permissions = {
            val user = authOrNull?.fetch()
            val everyone: Condition<User> = Condition.Always
            val self: Condition<User> = Condition { it._id eqNn user?._id }
            val admin: Condition<User> = if (user?.isSuperUser == true) Condition.Always else Condition.Never
            ModelPermissions(
                create = Condition.Never,
                read = everyone,
                readMask = mask {
                    it.hashedPassword.mask(value = "", unless = self or admin)
                    it.phone.mask(value = null, unless = self or admin)
                    it.isSuperUser.mask(value = false, unless = self or admin)
                },
                update = self or admin,
                delete = self or admin
            )
        }
    )

    val rest = path.path("rest") module ModelRestEndpoints(info)
}