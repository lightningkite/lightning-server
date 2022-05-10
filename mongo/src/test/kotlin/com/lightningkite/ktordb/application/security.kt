package com.lightningkite.ktordb.application

import com.lightningkite.ktordb.*
import com.lightningkite.ktordb.*

fun User.Companion.secureLazy(forUser: User) = User.chain.let {
    object : SecurityRules<User> {
        val isMyself = User.chain._id eq forUser._id
        override suspend fun read(filter: Condition<User>): Condition<User> = filter
        override suspend fun mask(model: User): User = model
        override suspend fun edit(
            filter: Condition<User>,
            modification: Modification<User>
        ): Pair<Condition<User>, Modification<User>> = Condition.Always<User>() to modification
        override suspend fun create(model: User): User = model
        override suspend fun replace(model: User): Pair<Condition<User>, User> = Condition.Always<User>() to model
        override suspend fun delete(filter: Condition<User>): Condition<User> = filter
        override suspend fun maxQueryTimeMs(): Long = 5000L
        override suspend fun sortAllowed(filter: SortPart<User>) = Condition.Always<User>()
    }
}

fun User.Companion.secureHigh(forUser: User) = User.chain.let {
    object : SecurityRules<User> {
        val isMyself = User.chain._id eq forUser._id
        override suspend fun read(filter: Condition<User>): Condition<User> = isMyself and filter
        override suspend fun mask(model: User): User = model
        override suspend fun edit(filter: Condition<User>, modification: Modification<User>): Pair<Condition<User>, Modification<User>> = isMyself to modification
        override suspend fun create(model: User): User = throw IllegalArgumentException()
        override suspend fun replace(model: User): Pair<Condition<User>, User> = isMyself to model
        override suspend fun delete(filter: Condition<User>): Condition<User> = isMyself and filter
        override suspend fun maxQueryTimeMs(): Long  = 5000L
        override suspend fun sortAllowed(filter: SortPart<User>) = Condition.Always<User>()
    }
}

fun Post.Companion.secure(forUser: User) = Post.chain.let {
    object : SecurityRules<Post> {
        override suspend fun sortAllowed(filter: SortPart<Post>) = Condition.Always<Post>()

        override suspend fun read(filter: Condition<Post>): Condition<Post> = filter
        override suspend fun mask(model: Post): Post = model
        override suspend fun edit(filter: Condition<Post>, modification: Modification<Post>): Pair<Condition<Post>, Modification<Post>> {
            if(modification.referencesField(PostFields.author)) throw SecurityException()
            if(modification.referencesField(PostFields.at)) throw SecurityException()
            return myPost to modification
        }

        override suspend fun create(model: Post): Post = model.copy(author = forUser._id, at = System.currentTimeMillis())
        override suspend fun replace(model: Post): Pair<Condition<Post>, Post> =
            myPost to model.copy(author = forUser._id, at = System.currentTimeMillis())

        val myPost: Condition<Post> get() = Post.chain.author eq forUser._id
        override suspend fun delete(filter: Condition<Post>): Condition<Post> = Condition.Never<Post>()
        override suspend fun maxQueryTimeMs(): Long = 5000L
    }
}
