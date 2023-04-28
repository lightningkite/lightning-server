# Automatically Generated REST Endpoints

By combining [typed endpoints](typed-endpoints.md), [authentication](authentication.md), and [databases](database.md), we can conveniently generate REST endpoints for any given model automatically.

```kotlin

object Server : ServerPathGroup(ServerPath.root) {
    val settingName = setting(name = "settingName", default = "defaultValue")
    val database = setting(name = "database", default = DatabaseSettings())
    val cache = setting(name = "cache", default = CacheSettings())
    val email = setting(name = "email", default = EmailSettings())
    val jwt = setting(name = "jwt", default = JwtSigner())
    val auth = AuthEndpoints(path("auth"))
    val meta = path("meta").metaEndpoints<User>(isAdmin = { it: User -> it.email.endsWith("@lightningkite.com") })
    val posts = PostEndpoints(path("posts"))
}

@Serializable
@DatabaseModel
@AdminTableColumns(["name", "number", "status"])
data class Post(
    override val _id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val name: String = "No Name",
    @References(User::class) val author: UUID,
    val content: String = "",
) : HasId<UUID>

class PostEndpoints(path: ServerPath): ServerPathGroup(path) {
    val info = ModelInfoWithDefault<User, Post, UUID>(
        getCollection = { Server.database().collection() },
        forUser = {  user ->
            this.withPermissions(
                ModelPermissions(
                    create = condition { it.author eq user._id },
                    read = condition { it.always },
                    update = condition { it.author eq user._id },
                    updateRestrictions = updateRestrictions {
                        it.author.cannotBeModified()
                    },
                    delete = condition { it.author eq user._id }
                )
            )
        },
        defaultItem = { user -> Post(author = user._id) }
    )
    val rest = ModelRestEndpoints(path("rest"), info)
}

//------------------
//  Everything below is from other examples.
//------------------

// Our auth endpoints.
class AuthEndpoints(path: ServerPath): ServerPathGroup(path) {
    // You'll make one of these if you set up auto-rest for users regardless.
    val userModelInfo = ModelInfo(
        getCollection = { Server.database().collection<User>() },
        forUser = { user: User -> this }
    )
    // Information about how to access users, including what to do if a user is not found using a certain email.
    // In this case, a user is simply created if it does not exist.
    val userAccess = userModelInfo.userEmailAccess { User(email = it) }
    // The basic auth endpoint information.  Required no matter what kind of authentication you're doing.
    val baseAuth = BaseAuthEndpoints(
        path = path("auth"),
        userAccess = userAccess,
        jwtSigner = Server.jwt
    )
    // Authenticates user via email magic link / PIN
    val emailAuth = EmailAuthEndpoints(
        base = baseAuth,
        emailAccess = userAccess,
        cache = Server.cache,
        email = Server.email
    )
}

@Serializable
@DatabaseModel
data class User(
    override val _id: UUID = UUID.randomUUID(),
    override val email: String,
) : HasId<UUID>, HasEmail
```
