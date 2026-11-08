package com.fingertipsmcp.wait

/** Whether a condition holds right now, and when to look again if nothing else happens. */
data class Check(val satisfied: Boolean, val recheckAtMs: Long? = null)

fun interface ConditionWatcher {
    suspend fun check(): Check
}
