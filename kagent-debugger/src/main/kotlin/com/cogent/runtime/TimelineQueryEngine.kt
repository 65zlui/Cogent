package com.cogent.runtime

// ============================================================
// TimelineQueryEngine — Graph-native execution query interface
// ============================================================

/**
 * Query engine for navigating and analyzing an execution [TimelineGraph].
 *
 * All queries use pre-built [TimelineIndices] for O(1) or O(log n) access.
 * No full-graph scans — indices are built at projection time.
 *
 * ### Graph queries
 * - [byStep] / [byTool] / [byTimeRange] — find nodes by criteria
 * - [descendants] / [ancestors] — recursive graph traversal
 * - [children] / [parents] — direct adjacency
 * - [criticalPath] — longest duration path through the DAG
 * - [filterByType] — event-type-based filtering
 */
class TimelineQueryEngine(private val graph: TimelineGraph) {

    private val indices = graph.indices

    // ================================================================
    // Node lookup
    // ================================================================

    /**
     * Find all node IDs for a given step name.
     * Includes both StepStart and StepEnd events for that step.
     */
    fun byStep(stepId: String): List<TimelineNode> {
        return indices.byStepId[stepId]
            ?.mapNotNull { indices.byNodeId[it] }
            ?: emptyList()
    }

    /**
     * Find all node IDs for a given tool name.
     * Includes both ToolCall and ToolResult events.
     */
    fun byTool(tool: String): List<TimelineNode> {
        return indices.byTool[tool]
            ?.mapNotNull { indices.byNodeId[it] }
            ?: emptyList()
    }

    /**
     * Find all nodes within a time range.
     *
     * Note: this is a linear scan since timestamps aren't indexed.
     * Acceptable for v0.6.2 given typical trace sizes (<10k events).
     */
    fun byTimeRange(start: Long, end: Long): List<TimelineNode> {
        return graph.nodes.filter { it.timestamp in start..end }
    }

    // ================================================================
    // Graph traversal
    // ================================================================

    /**
     * Direct children of a node (forward adjacency).
     */
    fun children(nodeId: String): List<TimelineNode> {
        return indices.adjacency[nodeId]
            ?.mapNotNull { indices.byNodeId[it] }
            ?: emptyList()
    }

    /**
     * Direct parents of a node (reverse adjacency).
     */
    fun parents(nodeId: String): List<TimelineNode> {
        return indices.reverseAdjacency[nodeId]
            ?.mapNotNull { indices.byNodeId[it] }
            ?: emptyList()
    }

    /**
     * All descendants of a node (recursive DFS).
     */
    fun descendants(nodeId: String): List<TimelineNode> {
        val result = mutableListOf<TimelineNode>()
        val visited = mutableSetOf<String>()
        dfsDescendants(nodeId, visited, result)
        return result
    }

    /**
     * All ancestors of a node (recursive DFS).
     */
    fun ancestors(nodeId: String): List<TimelineNode> {
        val result = mutableListOf<TimelineNode>()
        val visited = mutableSetOf<String>()
        dfsAncestors(nodeId, visited, result)
        return result
    }

    private fun dfsDescendants(
        nodeId: String,
        visited: MutableSet<String>,
        result: MutableList<TimelineNode>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)
        val children = indices.adjacency[nodeId] ?: return
        for (childId in children) {
            if (childId in visited) continue
            val childNode = indices.byNodeId[childId] ?: continue
            result.add(childNode)
            dfsDescendants(childId, visited, result)
        }
    }

    private fun dfsAncestors(
        nodeId: String,
        visited: MutableSet<String>,
        result: MutableList<TimelineNode>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)
        val parentsList = indices.reverseAdjacency[nodeId] ?: return
        for (parentId in parentsList) {
            if (parentId in visited) continue
            val parentNode = indices.byNodeId[parentId] ?: continue
            result.add(parentNode)
            dfsAncestors(parentId, visited, result)
        }
    }

    // ================================================================
    // Critical path — longest duration path through the DAG
    // ================================================================

    /**
     * Compute the critical path through the execution graph.
     *
     * Uses topological sort + DP to find the longest path by wall-clock
     * duration. Edge weight = `toNode.timestamp - fromNode.timestamp`.
     *
     * For v0.6.2 this operates on the full edge set. Span-based critical
     * path analysis is reserved for v0.6.3+.
     *
     * @return Nodes on the critical path, ordered from earliest to latest.
     */
    fun criticalPath(): List<TimelineNode> {
        if (graph.nodes.isEmpty()) return emptyList()

        // Topological sort of the DAG
        val topoOrder = topologicalSort()
        if (topoOrder.isEmpty()) return emptyList()

        // DP: longest path
        val dist = mutableMapOf<String, Long>()
        val prev = mutableMapOf<String, String?>()

        for (nodeId in topoOrder) {
            dist[nodeId] = dist[nodeId] ?: 0L

            val currentDist = dist[nodeId] ?: 0L
            val children = indices.adjacency[nodeId] ?: continue

            for (childId in children) {
                val childNode = indices.byNodeId[childId] ?: continue
                val currentNode = indices.byNodeId[nodeId] ?: continue

                // Edge weight = time difference (clamped to 0 to avoid negative weights)
                val weight = (childNode.timestamp - currentNode.timestamp).coerceAtLeast(0)
                val newDist = currentDist + weight

                if (newDist > (dist[childId] ?: 0L)) {
                    dist[childId] = newDist
                    prev[childId] = nodeId
                }
            }
        }

        // Find the node with maximum distance from source
        val (maxNodeId, _) = dist.maxByOrNull { it.value }
            ?: return listOf(graph.nodes.first())

        // Reconstruct path
        val pathIds = mutableListOf<String>()
        var current: String? = maxNodeId
        while (current != null) {
            pathIds.add(current)
            current = prev[current] ?: break
        }
        pathIds.reverse()

        return pathIds.mapNotNull { indices.byNodeId[it] }
    }

    /**
     * Topological sort via DFS (Kahn's algorithm simplified for DAG).
     */
    private fun topologicalSort(): List<String> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<String>()

        fun dfs(nodeId: String) {
            if (nodeId in visited) return
            visited.add(nodeId)
            val children = indices.adjacency[nodeId]
            if (children != null) {
                for (childId in children) {
                    dfs(childId)
                }
            }
            order.add(nodeId)
        }

        for (node in graph.nodes) {
            if (node.id !in visited) {
                dfs(node.id)
            }
        }

        return order.reversed()
    }

    // ================================================================
    // Event type filtering
    // ================================================================

    /**
     * Filter the graph to only nodes of the given event [types].
     * Edges where both endpoints survive the filter are preserved.
     *
     * Returns a new [TimelineGraph] — the original is unchanged.
     */
    fun filterByType(vararg types: Class<out RuntimeEvent>): TimelineGraph {
        val filteredNodeIds = graph.nodes
            .filter { node -> types.any { it.isInstance(node.event) } }
            .map { it.id }
            .toSet()

        val filteredNodes = graph.nodes.filter { it.id in filteredNodeIds }
        val filteredEdges = graph.edges.filter {
            it.fromNodeId in filteredNodeIds && it.toNodeId in filteredNodeIds
        }
        val filteredIndices = TimelineIndices.build(filteredNodes, filteredEdges)

        return graph.copy(
            nodes = filteredNodes,
            edges = filteredEdges,
            nodeCount = filteredNodes.size,
            indices = filteredIndices
        )
    }
}
