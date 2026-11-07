package android.content

/** Just enough of Android's class for [Intent] in a test on the JVM. */
class ComponentName(private val flat: String) {
    fun flattenToShortString(): String = flat

    companion object {
        @JvmStatic
        fun unflattenFromString(text: String): ComponentName? =
            if ('/' in text) ComponentName(text) else null
    }
}
