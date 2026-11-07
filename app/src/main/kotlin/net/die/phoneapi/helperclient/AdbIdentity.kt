package net.die.phoneapi.helperclient

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * What the app has learned about this phone's own adbd, kept in files beside the ADB key: its
 * serial, which names its mDNS records, and the last wireless-debugging port that took a
 * connection. Both are learned over a working connection, so neither comes from the network.
 */
internal class AdbIdentity(dir: File) {
    private val serialFile = File(dir, "adb-serial")
    private val portFile = File(dir, "adb-port")

    /** `ro.serialno`, or null until a connection has read it. */
    val serial: String?
        get() = read(serialFile)?.takeIf { validSerial(it) }

    /** The last port adbd accepted a connection on, or null. */
    val lastPort: Int?
        get() = read(portFile)?.toIntOrNull()?.let(::validAdbPort)

    /** Records what a working connection to adbd on [port] reported as `ro.serialno`. */
    fun remember(serial: String?, port: Int) {
        serial?.trim()?.takeIf { validSerial(it) }?.let { write(serialFile, it) }
        write(portFile, port.toString())
    }

    /**
     * Records what `getprop ro.serialno` printed, run through a helper that just registered. Every
     * phone that has had a helper up then knows its serial before its first wireless restart.
     */
    fun recordSerial(exitCode: Int, stdout: String) {
        if (exitCode == 0) rememberSerial(stdout.trim())
    }

    /** Records [serial] read from one of this phone's own mDNS records. */
    fun rememberSerial(serial: String) {
        if (validSerial(serial)) write(serialFile, serial)
    }

    /** Forgets [port] if it is the one remembered, after it failed to take a connection. */
    fun forgetPort(port: Int) {
        if (lastPort == port) portFile.delete()
    }

    private fun read(file: File): String? =
        try {
            if (file.isFile) file.readText().trim().ifEmpty { null } else null
        } catch (_: IOException) {
            null
        }

    private fun write(file: File, value: String) {
        try {
            file.writeText(value)
        } catch (_: IOException) {
            // Only a hint for the next search; that search works without it.
        }
    }
}

/** A serial adbd can put in a record name: what `ro.serialno` holds on real phones. */
private fun validSerial(serial: String): Boolean = SERIAL.matches(serial)

private val SERIAL = Regex("""[A-Za-z0-9._-]{1,64}""")

/**
 * True when [serviceName] is a record this phone's adbd published. adbd names its wireless records
 * `adb-<ro.serialno>-<random>`, so another phone or an emulator on the network has another name.
 */
internal fun isOwnAdbRecord(serviceName: String, serial: String): Boolean =
    serviceName.startsWith("adb-$serial-")

/** The serial in an adbd record name `adb-<serial>-<random>`, or null for another name. */
internal fun serialOfAdbRecord(serviceName: String): String? =
    ADB_RECORD.matchEntire(serviceName)?.groupValues?.get(1)?.takeIf { validSerial(it) }

private val ADB_RECORD = Regex("""adb-(.+)-[A-Za-z0-9]+""")

/**
 * The records a port search still has to resolve, in the order found. Only names [wanted] accepts
 * are queued. A record is resolved once while it is advertised; one lost and found again is queued
 * again, since adbd registers a new port that way.
 */
internal class ResolveQueue<T : Any>(private val wanted: (String) -> Boolean) {
    private val pending = ArrayDeque<Pair<String, T>>()
    private val seen = HashSet<String>()

    fun found(name: String, record: T) {
        if (wanted(name) && seen.add(name)) pending.add(name to record)
    }

    fun lost(name: String) {
        seen.remove(name)
        pending.removeAll { it.first == name }
    }

    fun next(): T? = pending.removeFirstOrNull()?.second
}

/** This device's own addresses, which a record's host must be one of. */
internal fun localAddresses(): Set<InetAddress> =
    try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.toSet()
            .orEmpty()
    } catch (e: SocketException) {
        Log.w(IDENTITY_TAG, "Could not list network interfaces", e)
        emptySet()
    }

private const val IDENTITY_TAG = "PhoneApiHelper"
