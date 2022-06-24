package com.lightningkite.ktordb.application

import com.lightningkite.ktordb.*
import java.util.*

fun User.Companion.openPermissions(forUser: User): ModelPermissions<User> {
    val noOne = Condition.Never<User>()
    val me = startChain<User>()._id eq forUser._id
    val anyone = Condition.Always<User>()
    return ModelPermissions<User>(
        create = anyone,
        read = anyone,
        update = anyone,
        delete = anyone
    )
}

fun User.Companion.selfWithoutCreate(forUser: User): ModelPermissions<User> {
    val noOne = Condition.Never<User>()
    val me = startChain<User>()._id eq forUser._id
    val anyone = Condition.Always<User>()
    return ModelPermissions<User>(
        create = noOne,
        read = me,
        update = me,
        delete = me
    )
}

fun Post.Companion.permissions(forUser: User): ModelPermissions<Post> {
    val noOne = Condition.Never<Post>()
    val author = condition<Post> { it.author eq forUser._id }
    val anyone = Condition.Always<Post>()
    return ModelPermissions<Post>(
        create = anyone,
        read = anyone,
        readFields = listOf(
            ModelPermissions.Read(Post::at, noOne, 0L),
            ModelPermissions.Read(Post::author, noOne, UUID.randomUUID()),
        ).associateBy { it.property },
        update = author,
        updateFields = listOf(
            ModelPermissions.Update(Post::at, noOne),
            ModelPermissions.Update(Post::author, noOne),
        ).associateBy { it.property },
        delete = noOne
    )
}

fun FieldCollection<Post>.forUser(user: User) = withPermissions(Post.permissions(user))
    .interceptCreate { it.copy(author = user._id, at = System.currentTimeMillis()) }