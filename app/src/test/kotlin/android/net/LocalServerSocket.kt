package android.net

import java.io.IOException

/**
 * Stands in for the platform socket in JVM unit tests, where nothing can bind an abstract name. It
 * fails as Android does when the name is already bound. The test classpath puts this ahead of the
 * Android stub jar.
 */
class LocalServerSocket(name: String) {
    init {
        throw IOException("Address already in use: $name")
    }
}
