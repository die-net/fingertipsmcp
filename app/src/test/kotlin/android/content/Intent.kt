package android.content

import android.net.Uri

/** Just enough of Android's class for a test on the JVM to read back what was put in one. */
class Intent(val action: String?) {
    var data: Uri? = null
        private set

    var `package`: String? = null
        private set

    var component: ComponentName? = null
        private set

    val categories = LinkedHashSet<String>()
    val extras = LinkedHashMap<String, String>()

    var flags = 0
        private set

    fun setData(data: Uri?): Intent = apply { this.data = data }

    fun setPackage(name: String?): Intent = apply { `package` = name }

    fun setComponent(component: ComponentName?): Intent = apply { this.component = component }

    fun addCategory(category: String): Intent = apply { categories += category }

    fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value.orEmpty() }

    fun setFlags(flags: Int): Intent = apply { this.flags = flags }

    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }

    companion object {
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
