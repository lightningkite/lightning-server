import kotlinx.serialization.Serializable

@Serializable
data class Test(val x: Int): ()->Int {
    override fun invoke(): Int = x
}