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

// ============================================================
// v0.6.1 — Timeline DAG
// ============================================================

/**
 * Types of causal/structural edges in the timeline DAG.
 *
 * | Type | Source → Target | Semantics |
 * |------|----------------|-----------|
 * | SEQUENTIAL | node[N] → node[N+1] | Chronological event ordering |
 * | CAUSAL | StepStart → StepEnd | Execution scope boundary |
 * | TOOL_FLOW | ToolCall → ToolResult | Tool invocation lifecycle |
 */
enum class EdgeType {
    SEQUENTIAL,
    CAUSAL,
    TOOL_FLOW
}

/**
 * A directed edge in the timeline causality graph.
 *
 * @param fromNodeId Source node ID.
 * @param toNodeId Target node ID.
 * @param type The nature of the relationship.
 */
data class TimelineEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val type: EdgeType
)

/**
 * A DAG-based execution timeline with explicit causality edges.
 *
 * Unlike [Timeline] which is a flat ordered list,
 * [TimelineGraph] captures multiple relationship dimensions:
 *
 * - **SEQUENTIAL** edges form the chronological backbone
 * - **CAUSAL** edges link scope boundaries (StepStart↔StepEnd)
 * - **TOOL_FLOW** edges link tool invocations (ToolCall↔ToolResult)
 *
 * @param traceId The execution trace this graph covers.
 * @param nodes All timeline nodes (chronologically ordered).
 * @param edges Causality and ordering edges forming the DAG.
 * @param startTime Timestamp of the first node.
 * @param endTime Timestamp of the last node.
 * @param nodeCount Total number of nodes.
 */
data class TimelineGraph(
    val traceId: String,
    val nodes: List<TimelineNode>,
    val edges: List<TimelineEdge>,
    val startTime: Long,
    val endTime: Long,
    val nodeCount: Int
)

/**
 * Builds [TimelineGraph] instances from raw [EventStoreEntry] records.
 *
 * Converts the flat event log into a DAG with three edge types:
 * - SEQUENTIAL edges for chronological ordering
 * - CAUSAL edges linking StepStart↔StepEnd, RunStart↔RunEnd
 * - TOOL_FLOW edges linking ToolCall↔ToolResult
 */
internal class TimelineBuilder {

    /**
     * Build a [TimelineGraph] from a chronologically-ordered event list.
     * Returns null if [events] is empty.
     */
    fun build(events: List<EventStoreEntry>): TimelineGraph? {
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

        val edges = buildEdges(nodes)

        return TimelineGraph(
            traceId = traceId,
            nodes = nodes,
            edges = edges,
            startTime = events.first().timestamp,
            endTime = events.last().timestamp,
            nodeCount = events.size
        )
    }

    /**
     * Construct DAG edges from an ordered node list.
     *
     * Produces three edge types:
     * 1. [EdgeType.SEQUENTIAL] — between every consecutive node pair
     * 2. [EdgeType.CAUSAL] — pairing StepStart↔StepEnd and RunStart↔RunEnd via stack
     * 3. [EdgeType.TOOL_FLOW] — pairing ToolCall↔ToolResult via stack
     */
    private fun buildEdges(nodes: List<TimelineNode>): List<TimelineEdge> {
        val edges = mutableListOf<TimelineEdge>()
        val stepStack = ArrayDeque<String>()   // pending StepStart node IDs
        val toolStack = ArrayDeque<String>()   // pending ToolCall node IDs
        var runStartNodeId: String? = null

        for ((index, node) in nodes.withIndex()) {
            // 1. SEQUENTIAL: chronological backbone
            if (index > 0) {
                edges.add(TimelineEdge(
                    fromNodeId = nodes[index - 1].id,
                    toNodeId = node.id,
                    type = EdgeType.SEQUENTIAL
                ))
            }

            // 2. Scope/causality pairing and tool flow
            when (node.event) {
                is RuntimeEvent.RunStart -> runStartNodeId = node.id
                is RuntimeEvent.RunEnd -> {
                    val startId = runStartNodeId
                    if (startId != null) {
                        edges.add(TimelineEdge(startId, node.id, EdgeType.CAUSAL))
                    }
                }
                is RuntimeEvent.StepStart -> stepStack.addLast(node.id)
                is RuntimeEvent.StepEnd -> {
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.removeLast(), node.id, EdgeType.CAUSAL))
                    }
                }
                is RuntimeEvent.ToolCall -> toolStack.addLast(node.id)
                is RuntimeEvent.ToolResult -> {
                    if (toolStack.isNotEmpty()) {
                        edges.add(TimelineEdge(toolStack.removeLast(), node.id, EdgeType.TOOL_FLOW))
                    }
                }
                else -> { /* MemoryChange, DerivedRecompute — no scope edges */ }
            }
        }

        return edges
    }

    /**
     * Filter a timeline graph to only include events matching the given [types].
     * Edges whose both endpoints survive the filter are preserved.
     */
    fun filterByType(graph: TimelineGraph, vararg types: Class<out RuntimeEvent>): TimelineGraph {
        val filteredNodeIds = graph.nodes
            .filter { node -> types.any { it.isInstance(node.event) } }
            .map { it.id }
            .toSet()

        val filteredNodes = graph.nodes.filter { it.id in filteredNodeIds }
        val filteredEdges = graph.edges.filter {
            it.fromNodeId in filteredNodeIds && it.toNodeId in filteredNodeIds
        }

        return graph.copy(
            nodes = filteredNodes,
            edges = filteredEdges,
            nodeCount = filteredNodes.size
        )
    }
}
