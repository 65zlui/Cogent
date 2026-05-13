package com.cogent.runtime

import com.cogent.memory.core.Memory

// ============================================================
// RuntimeDebugger — Execution observability & analysis tool
// ============================================================

/**
 * Debugger for inspecting execution history.
 *
 * Provides read-only access to recorded events, timeline reconstruction,
 * and state inspection. Separated from [KAgentRuntime] to keep execution
 * and observability concerns decoupled.
 *
 * ### v0.6.1 capabilities:
 * - [timeline] — reconstruct a causal DAG ([TimelineGraph]) from stored events
 * - [inspectState] — return state snapshot by version (deferred to v0.6.1)
 * - [queryEvents] — filter events by trace, type, time range
 *
 * Usage:
 * ```kotlin
 * val dbg = runtime.debugger()
 * val graph = dbg.timeline("trace_123")
 * graph?.edges?.forEach { edge ->
 *     println("${edge.type}: ${edge.fromNodeId} → ${edge.toNodeId}")
 * }
 * ```
 */
class RuntimeDebugger(
    private val eventStore: EventStore,
    private val memory: Memory
) {

    private val timelineBuilder = TimelineBuilder()

    // ================================================================
    // Timeline
    // ================================================================

    /**
     * Reconstruct the execution timeline DAG for a given [traceId].
     *
     * Returns a [TimelineGraph] with causality edges linking
     * StepStart↔StepEnd, ToolCall↔ToolResult, and sequential ordering,
     * or null if no events exist for this traceId.
     *
     * @param traceId The execution trace to reconstruct.
     * @return [TimelineGraph] or null.
     */
    fun timeline(traceId: String): TimelineGraph? {
        val events = eventStore.getEvents(traceId)
        return timelineBuilder.build(events)
    }

    /**
     * Return all traceIds currently in the event store.
     */
    fun traceIds(): List<String> {
        return eventStore.getAllEvents()
            .map { it.traceId }
            .distinct()
            .sorted()
    }

    // ================================================================
    // State inspection (v0.6.1 — snapshot table)
    // ================================================================

    /**
     * Inspect the state at a given [stateVersion].
     *
     * In v0.6.0 this always returns null. A state snapshot table
     * (checkpoint-based versioned state storage) will be added in v0.6.1.
     *
     * Until then, use [KAgentRuntime.getState] for live state access.
     *
     * @param stateVersion The version to query.
     * @return State snapshot at that version, or null if unavailable.
     */
    fun inspectState(stateVersion: Long): Map<String, Any?>? {
        // v0.6.0 — placeholder, needs snapshot table in v0.6.1
        return null
    }

    // ================================================================
    // Event query
    // ================================================================

    /**
     * Query stored events with optional filters.
     *
     * @param filter Filter criteria (traceId, event types, time range).
     * @return Matching [RuntimeEvent]s in chronological order.
     */
    fun queryEvents(filter: EventFilter = EventFilter()): List<RuntimeEvent> {
        return eventStore.getAllEvents()
            .filter { entry -> matches(entry, filter) }
            .map { it.event }
    }

    private fun matches(entry: EventStoreEntry, filter: EventFilter): Boolean {
        if (filter.traceId != null && entry.traceId != filter.traceId) return false

        if (filter.eventTypes != null && filter.eventTypes.isNotEmpty()) {
            if (filter.eventTypes.none { it.isInstance(entry.event) }) return false
        }

        if (filter.fromTimestamp != null && entry.timestamp < filter.fromTimestamp) return false
        if (filter.toTimestamp != null && entry.timestamp > filter.toTimestamp) return false

        return true
    }

    /**
     * Total stored events across all traces.
     */
    fun eventCount(): Int = eventStore.size()
}

/**
 * Filter criteria for [RuntimeDebugger.queryEvents].
 *
 * All fields are optional — an empty filter returns all events.
 *
 * @param traceId Restrict to a specific execution trace.
 * @param eventTypes Restrict to specific [RuntimeEvent] subtypes.
 * @param fromTimestamp Earliest event timestamp (inclusive).
 * @param toTimestamp Latest event timestamp (inclusive).
 */
data class EventFilter(
    val traceId: String? = null,
    val eventTypes: Set<Class<out RuntimeEvent>>? = null,
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null
)
