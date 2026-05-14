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
 * | SEQUENTIAL | landmark → landmark | Chronological ordering of significant events |
 * | CAUSAL | StepStart → contained event | Execution scope containment |
 * | TOOL_FLOW | ToolCall → ToolResult | Tool invocation lifecycle |
 * | STREAM_FLOW | StreamStart → StreamDelta → StreamEnd | Streaming output lifecycle |
 */
enum class EdgeType {
    SEQUENTIAL,
    CAUSAL,
    TOOL_FLOW,
    STREAM_FLOW
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
 * @param indices Pre-computed query indices for fast graph traversal.
 */
data class TimelineGraph(
    val traceId: String,
    val nodes: List<TimelineNode>,
    val edges: List<TimelineEdge>,
    val startTime: Long,
    val endTime: Long,
    val nodeCount: Int,
    val indices: TimelineIndices = TimelineIndices.EMPTY
)

// ============================================================
// v0.6.2 — Queryable Execution Graph
// ============================================================

/**
 * Pre-computed indices for fast graph traversal and query.
 *
 * Built once during [TimelineProjection.project] — immutable after construction.
 *
 * @param byNodeId All nodes keyed by their [TimelineNode.id].
 * @param byStepId StepStart node IDs grouped by step name.
 * @param byTool ToolCall node IDs grouped by tool name.
 * @param adjacency Forward edge map: nodeId → list of child node IDs.
 * @param reverseAdjacency Reverse edge map: nodeId → list of parent node IDs.
 */
data class TimelineIndices(
    val byNodeId: Map<String, TimelineNode>,
    val byStepId: Map<String, List<String>>,
    val byTool: Map<String, List<String>>,
    val adjacency: Map<String, List<String>>,
    val reverseAdjacency: Map<String, List<String>>
) {
    companion object {
        val EMPTY = TimelineIndices(
            byNodeId = emptyMap(),
            byStepId = emptyMap(),
            byTool = emptyMap(),
            adjacency = emptyMap(),
            reverseAdjacency = emptyMap()
        )

        /**
         * Build indices from an already-constructed node and edge list.
         */
        fun build(nodes: List<TimelineNode>, edges: List<TimelineEdge>): TimelineIndices {
            val byNodeId = nodes.associateBy { it.id }

            val byStepId = mutableMapOf<String, MutableList<String>>()
            val byTool = mutableMapOf<String, MutableList<String>>()
            val adjacency = mutableMapOf<String, MutableSet<String>>()
            val reverseAdjacency = mutableMapOf<String, MutableSet<String>>()

            for (node in nodes) {
                when (val event = node.event) {
                    is RuntimeEvent.StepStart ->
                        byStepId.getOrPut(event.stepId) { mutableListOf() }.add(node.id)
                    is RuntimeEvent.StepEnd ->
                        byStepId.getOrPut(event.stepId) { mutableListOf() }.add(node.id)
                    is RuntimeEvent.ToolCall ->
                        byTool.getOrPut(event.tool) { mutableListOf() }.add(node.id)
                    is RuntimeEvent.ToolResult ->
                        byTool.getOrPut(event.tool) { mutableListOf() }.add(node.id)
                    else -> {}
                }
            }

            for (edge in edges) {
                adjacency.getOrPut(edge.fromNodeId) { mutableSetOf() }.add(edge.toNodeId)
                reverseAdjacency.getOrPut(edge.toNodeId) { mutableSetOf() }.add(edge.fromNodeId)
            }

            return TimelineIndices(
                byNodeId = byNodeId,
                byStepId = byStepId.mapValues { it.value.toList() },
                byTool = byTool.mapValues { it.value.toList() },
                adjacency = adjacency.mapValues { it.value.toList() },
                reverseAdjacency = reverseAdjacency.mapValues { it.value.toList() }
            )
        }
    }
}

/**
 * A time-bounded execution span.
 *
 * Reserved for v0.6.3+ tracing model (OpenTelemetry-style).
 * In v0.6.2 this is a placeholder — spans are not fully implemented.
 *
 * @param startNode The node that opens this span.
 * @param endNode The node that closes this span.
 * @param durationMs Wall-clock duration in milliseconds.
 */
data class ExecutionSpan(
    val startNode: String,
    val endNode: String,
    val durationMs: Long
)

/**
 * Projection layer that converts raw EventStore records into
 * a fully-indexed, queryable [TimelineGraph].
 *
 * This is the production entry point for graph construction.
 * Unlike [TimelineBuilder] which produces a bare graph,
 * [TimelineProjection] attaches query indices and timing metadata.
 */
class TimelineProjection {

    private val builder = TimelineBuilder()

    /**
     * Project stored events into a [TimelineGraph] with query indices.
     *
     * @param events Chronologically ordered events from [EventStore].
     * @return Fully indexed [TimelineGraph], or null if [events] is empty.
     */
    fun project(events: List<EventStoreEntry>): TimelineGraph? {
        val graph = builder.build(events) ?: return null
        val indices = TimelineIndices.build(graph.nodes, graph.edges)
        return graph.copy(indices = indices)
    }
}

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
     * Produces four edge types:
     * 1. [EdgeType.SEQUENTIAL] — between every consecutive node (chronological backbone).
     *    This guarantees full connectivity: every node has at least one incoming edge
     *    from its immediate predecessor, and one outgoing edge to its successor.
     * 2. [EdgeType.CAUSAL] — StepStart → each contained event, last contained → StepEnd, RunStart↔RunEnd
     * 3. [EdgeType.TOOL_FLOW] — pairing ToolCall↔ToolResult via stack
     * 4. [EdgeType.STREAM_FLOW] — pairing StreamStart→StreamDelta→StreamEnd via stack
     *
     * Note: MemoryChange and other non-step events fire asynchronously because
     * [RuntimeHeart.step] schedules tasks via `scope.launch`. Their chronological
     * position may not align with their logical step boundary. The SEQUENTIAL chain
     * guarantees connectivity; CAUSAL edges are best-effort semantic enrichment.
     */
    private fun buildEdges(nodes: List<TimelineNode>): List<TimelineEdge> {
        val edges = mutableListOf<TimelineEdge>()
        val stepStack = ArrayDeque<String>()   // pending StepStart node IDs
        val toolStack = ArrayDeque<String>()   // pending ToolCall node IDs
        val streamStack = ArrayDeque<String>() // pending StreamStart node IDs
        var runStartNodeId: String? = null
        var prevNodeId: String? = null

        // Track in-scope node IDs per step for StepStart→contained and last→StepEnd edges
        val stepContained = mutableMapOf<String, MutableList<String>>()

        for (node in nodes) {
            // 1. SEQUENTIAL: link every consecutive node — guarantees full graph connectivity
            if (prevNodeId != null) {
                edges.add(TimelineEdge(
                    fromNodeId = prevNodeId,
                    toNodeId = node.id,
                    type = EdgeType.SEQUENTIAL
                ))
            }
            prevNodeId = node.id

            // 2. Scope/causality, tool flow, and stream flow pairing
            when (node.event) {
                is RuntimeEvent.RunStart -> runStartNodeId = node.id

                is RuntimeEvent.RunEnd -> {
                    val startId = runStartNodeId
                    if (startId != null) {
                        edges.add(TimelineEdge(startId, node.id, EdgeType.CAUSAL))
                    }
                }

                is RuntimeEvent.StepStart -> {
                    stepStack.addLast(node.id)
                    stepContained[node.id] = mutableListOf()
                }

                is RuntimeEvent.StepEnd -> {
                    if (stepStack.isNotEmpty()) {
                        val stepStartId = stepStack.removeLast()
                        edges.add(TimelineEdge(stepStartId, node.id, EdgeType.CAUSAL))
                        // Link last contained event → StepEnd
                        val contained = stepContained[stepStartId]
                        if (!contained.isNullOrEmpty()) {
                            edges.add(TimelineEdge(contained.last(), node.id, EdgeType.CAUSAL))
                        }
                        stepContained.remove(stepStartId)
                    }
                }

                is RuntimeEvent.ToolCall -> {
                    toolStack.addLast(node.id)
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }

                is RuntimeEvent.ToolResult -> {
                    if (toolStack.isNotEmpty()) {
                        edges.add(TimelineEdge(toolStack.removeLast(), node.id, EdgeType.TOOL_FLOW))
                    }
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }

                is RuntimeEvent.StreamStart -> {
                    streamStack.addLast(node.id)
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }

                is RuntimeEvent.StreamDelta -> {
                    if (streamStack.isNotEmpty()) {
                        edges.add(TimelineEdge(streamStack.last(), node.id, EdgeType.STREAM_FLOW))
                    }
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }

                is RuntimeEvent.StreamEnd -> {
                    val streamStartId = if (streamStack.isNotEmpty()) streamStack.removeLast() else null
                    if (streamStartId != null) {
                        edges.add(TimelineEdge(streamStartId, node.id, EdgeType.STREAM_FLOW))
                    }
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }

                is RuntimeEvent.MemoryChange, is RuntimeEvent.DerivedRecompute -> {
                    if (stepStack.isNotEmpty()) {
                        edges.add(TimelineEdge(stepStack.last(), node.id, EdgeType.CAUSAL))
                        stepContained[stepStack.last()]?.add(node.id)
                    }
                }
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
