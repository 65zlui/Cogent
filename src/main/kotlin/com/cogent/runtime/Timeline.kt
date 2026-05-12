package com.cogent.runtime

// ============================================================
// Timeline — Execution observability data model
// ============================================================

/**
 * A single node in the execution timeline.
 *
 * Each [RuntimeEvent] recorded during execution produces one TimelineNode.
 * Nodes are ordered chronologically and linked via [parentId] into a
 * causality chain within a single [traceId].
 *
 * @param id Unique node identifier (format: "node_{index}_{traceId}").
 * @param event The original runtime event.
 * @param timestamp Wall-clock time when the event was recorded.
 * @param traceId Correlates this node to an execution trace.
 * @param parentId ID of the preceding node in the same trace (null for root).
 * @param stateVersion Monotonic version assigned by [EventStore.record].
 *                     Used to reference state snapshots via [RuntimeDebugger.inspectState].
 */
data class TimelineNode(
    val id: String,
    val event: RuntimeEvent,
    val timestamp: Long,
    val traceId: String,
    val parentId: String?,
    val stateVersion: Long
)

/**
 * An ordered, causally-linked execution timeline for a single [traceId].
 *
 * Constructed by [TimelineBuilder] from stored [EventStoreEntry] records.
 * Represents the full observable history of one [execute] or [stream] call.
 *
 * @param traceId The execution trace this timeline covers.
 * @param nodes Chronologically ordered nodes (earliest first).
 * @param startTime Timestamp of the first node.
 * @param endTime Timestamp of the last node.
 * @param nodeCount Total number of nodes in this timeline.
 */
data class Timeline(
    val traceId: String,
    val nodes: List<TimelineNode>,
    val startTime: Long,
    val endTime: Long,
    val nodeCount: Int
)

/**
 * Builds [Timeline] instances from raw [EventStoreEntry] records.
 *
 * Converts the flat event log into a causally-linked node sequence.
 * For v0.6.0, the timeline is a simple linear chain where each node
 * points to its chronological predecessor via [TimelineNode.parentId].
 *
 * Future versions (v0.6.1+) may construct DAG-based causality graphs
 * by linking StepStart↔StepEnd, ToolCall↔ToolResult, etc.
 */
internal class TimelineBuilder {

    /**
     * Build a [Timeline] from a chronologically-ordered event list.
     * Returns null if [events] is empty.
     */
    fun build(events: List<EventStoreEntry>): Timeline? {
        if (events.isEmpty()) return null

        val traceId = events.first().traceId
        val nodes = events.mapIndexed { index, entry ->
            TimelineNode(
                id = "node_${index}_${entry.traceId}",
                event = entry.event,
                timestamp = entry.timestamp,
                traceId = entry.traceId,
                parentId = if (index > 0) "node_${index - 1}_${entry.traceId}" else null,
                stateVersion = entry.stateVersion
            )
        }

        return Timeline(
            traceId = traceId,
            nodes = nodes,
            startTime = events.first().timestamp,
            endTime = events.last().timestamp,
            nodeCount = events.size
        )
    }

    /**
     * Filter a timeline to only include events matching the given [types].
     */
    fun filterByType(timeline: Timeline, vararg types: Class<out RuntimeEvent>): Timeline {
        val filtered = timeline.nodes.filter { node ->
            types.any { it.isInstance(node.event) }
        }
        return timeline.copy(nodes = filtered, nodeCount = filtered.size)
    }
}
