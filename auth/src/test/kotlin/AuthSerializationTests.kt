import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.HasId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.uuid.Uuid

@Serializable
data class User(
    override val _id: Uuid
) : HasId<Uuid> {
    companion object : PrincipalType<User, Uuid> {
        override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
        override val subjectSerializer: KSerializer<User> = serializer()

        context(server: ServerRuntime)
        override suspend fun fetch(id: Uuid): User = User(id)
    }
}

class AuthSerializationTests {
    @Test
    fun idSerialization() {

    }
}