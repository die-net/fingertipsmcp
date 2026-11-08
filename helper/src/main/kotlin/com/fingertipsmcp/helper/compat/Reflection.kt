package com.fingertipsmcp.helper.compat

import java.lang.reflect.InvocationTargetException

/**
 * Runs [block], which looks up or calls hidden framework code by reflection, and lets only runtime
 * exceptions out. A Binder call can only report runtime exceptions back to the app, and the
 * helper's own loops catch only those, so a checked one would reach the app as a bare failure or
 * stop the helper. A runtime exception the called method threw comes out as itself, so a
 * `SecurityException` stays one. Anything else, such as a missing class or method or a checked
 * exception from the call, becomes an [IllegalStateException] that names [what].
 */
internal inline fun <T> reflective(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: InvocationTargetException) {
        val thrown = e.targetException ?: e
        throw thrown as? RuntimeException ?: IllegalStateException("$what failed: $thrown", thrown)
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException("$what failed: $e", e)
    }
