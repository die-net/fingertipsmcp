package net.die.phoneapi.helper.tree

/**
 * Whether the attached app client wants UiAutomation kept up. The hold belongs to the client that
 * set it, so a new client (an app process that restarted while this helper ran) starts with none,
 * and a hold left by a dead one does not keep the session busy.
 */
internal class ClientHold(private val client: () -> Any?) {
    @Volatile private var holder: Any? = null

    /** Records whether the current client holds. */
    fun set(hold: Boolean) {
        holder = if (hold) client() else null
    }

    val held: Boolean
        get() = holder?.let { it == client() } ?: false
}
