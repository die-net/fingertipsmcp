package net.die.phoneapi.helper.compat

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReflectionTest {
    /** Stands in for a hidden framework service. */
    @Suppress("unused") // Called only by reflection.
    class Service {
        fun denied(): Unit = throw SecurityException("caller uid 10123")

        fun dead(): Unit = throw IOException("DeadObjectException")

        fun answer(): Int = ANSWER
    }

    private val service = Service()

    private fun call(name: String): Any? =
        reflective("Service.$name") { Service::class.java.getMethod(name).invoke(service) }

    @Test
    fun `a runtime exception from the call comes out as itself`() {
        val e = assertThrows<SecurityException> { call("denied") }
        assertEquals("caller uid 10123", e.message)
    }

    @Test
    fun `a checked exception from the call becomes a runtime one`() {
        val e = assertThrows<IllegalStateException> { call("dead") }
        assertEquals(IOException::class.java, e.cause?.javaClass)
    }

    @Test
    fun `a missing method becomes a runtime exception`() {
        val e = assertThrows<IllegalStateException> { call("gone") }
        assertEquals(NoSuchMethodException::class.java, e.cause?.javaClass)
    }

    @Test
    fun `a missing class becomes a runtime exception`() {
        val e =
            assertThrows<IllegalStateException> {
                reflective("IGone") { Class.forName("android.os.IGone") }
            }
        assertEquals(ClassNotFoundException::class.java, e.cause?.javaClass)
    }

    @Test
    fun `a result passes through`() {
        assertEquals(ANSWER, call("answer"))
    }

    private companion object {
        const val ANSWER = 42
    }
}
