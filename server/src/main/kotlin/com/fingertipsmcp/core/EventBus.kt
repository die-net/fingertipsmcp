package com.fingertipsmcp.core

import com.fingertipsmcp.model.Event
import com.fingertipsmcp.model.EventData
import com.fingertipsmcp.model.encodeEventData
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject

/** Process-wide fan-out of device events to WebSocket clients and the wait engine. */
class EventBus {
    private val seq = AtomicLong()
    private val flow =
        MutableSharedFlow<Event>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<Event> = flow.asSharedFlow()

    /** How many collectors are currently reading [events]. */
    val subscribers: StateFlow<Int> = flow.subscriptionCount

    val lastSeq: Long
        get() = seq.get()

    /** Publishes a [type] event with [data], or with an empty `data` when it is null. */
    fun emit(type: String, data: EventData? = null) {
        flow.tryEmit(next(type, data))
    }

    /** Same clock and sequence as [emit], without publishing on [events]. */
    fun next(type: String, data: EventData? = null): Event =
        Event(
            type,
            System.currentTimeMillis(),
            seq.incrementAndGet(),
            data?.let(ApiJson::encodeEventData) ?: JsonObject(emptyMap()),
        )
}
