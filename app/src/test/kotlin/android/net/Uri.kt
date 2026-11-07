package android.net

/** Just enough of Android's class for a test on the JVM. */
class Uri private constructor(private val text: String) {
    override fun toString(): String = text

    companion object {
        @JvmStatic fun parse(text: String): Uri = Uri(text)
    }
}
