package com.cogent.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TimelineDebuggerTest {

    // ================================================================
    // EventStore
    // ================================================================

    @Test
    fun `event store assigns incrementing state versions`() {
        val store = EventStore()
        val e1 = store.record("trace-a", RuntimeEvent.RunStart("trace-a"))
        val e2 = store.record("trace-a", RuntimeEvent.StepStart("s1"))
        val e3 = store.record("trace-a", RuntimeEvent.StepEnd("s1"))

        assertEquals(1, e1.stateVersion, "first event gets version 1")
        assertEquals(2, e2.stateVersion, "second event gets version 2")
        assertEquals(3, e3.stateVersion, "third event gets version 3")
    }

    @Test
    fun `event store getEvents returns events for traceId`() {
        val store = EventStore()
        store.record("trace-a", RuntimeEvent.RunStart("trace-a"))
        store.record("trace-b", RuntimeEvent.RunStart("trace-b"))
        store.record("trace-a", RuntimeEvent.StepStart("s1"))

        val traceA = store.getEvents("trace-a")
        assertEquals(2, traceA.size)
        assertTrue(traceA.all { it.traceId == "trace-a" })
    }

    @Test
    fun `event store getEvents respects maxResults`() {
        val store = EventStore()
        repeat(10) { i ->
            store.record("trace-x", RuntimeEvent.RunStart("trace-x"))
        }

        val events = store.getEvents("trace-x", maxResults = 3)
        assertEquals(3, events.size)
    }

    @Test
    fun `event store getAllEvents returns everything`() {
        val store = EventStore()
        store.record("t1", RuntimeEvent.RunStart("t1"))
        store.record("t2", RuntimeEvent.RunStart("t2"))

        assertEquals(2, store.getAllEvents().size)
    }

    @Test
    fun `event store size and clear`() {
        val store = EventStore()
        assertEquals(0, store.size())

        store.record("t1", RuntimeEvent.RunStart("t1"))
        assertEquals(1, store.size())

        store.clear()
        assertEquals(0, store.size())
    }

    @Test
    fun `event store caps at maxEvents`() {
        val store = EventStore(maxEvents = 5)
        repeat(10) { i ->
            store.record("t-cap", RuntimeEvent.RunStart("t-cap"))
        }

        assertEquals(5, store.size())
    }

    // ================================================================
    // TimelineBuilder — TimelineGraph
    // ================================================================

    @Test
    fun `timeline builder returns null for empty events`() {
        val builder = TimelineBuilder()
        assertNull(builder.build(emptyList()))
    }

    @Test
    fun `timeline graph has trace metadata`() {
        val store = EventStore()
        store.record("tl-1", RuntimeEvent.RunStart("tl-1"))
        store.record("tl-1", RuntimeEvent.StepStart("s1"))
        store.record("tl-1", RuntimeEvent.StepEnd("s1"))
        store.record("tl-1", RuntimeEvent.RunEnd("tl-1"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-1"))

        assertNotNull(graph)
        assertEquals("tl-1", graph.traceId)
        assertEquals(4, graph.nodeCount)
        assertNotNull(graph.startTime)
        assertNotNull(graph.endTime)
        assertTrue(graph.endTime >= graph.startTime)
    }

    @Test
    fun `timeline graph creates SEQUENTIAL edges between consecutive nodes`() {
        val store = EventStore()
        store.record("tl-seq", RuntimeEvent.RunStart("tl-seq"))
        store.record("tl-seq", RuntimeEvent.StepStart("s1"))
        store.record("tl-seq", RuntimeEvent.StepEnd("s1"))
        store.record("tl-seq", RuntimeEvent.RunEnd("tl-seq"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-seq"))

        assertNotNull(graph)
        assertEquals(4, graph.nodes.size)

        // SEQUENTIAL: node[i] → node[i+1] for i=0,1,2
        val seqEdges = graph.edges.filter { it.type == EdgeType.SEQUENTIAL }
        assertEquals(3, seqEdges.size, "should have 3 SEQUENTIAL edges for 4 nodes")

        assertEquals(graph.nodes[0].id, seqEdges[0].fromNodeId)
        assertEquals(graph.nodes[1].id, seqEdges[0].toNodeId)

        assertEquals(graph.nodes[1].id, seqEdges[1].fromNodeId)
        assertEquals(graph.nodes[2].id, seqEdges[1].toNodeId)

        assertEquals(graph.nodes[2].id, seqEdges[2].fromNodeId)
        assertEquals(graph.nodes[3].id, seqEdges[2].toNodeId)

        // Verify parentId backward compat
        assertNull(graph.nodes[0].parentId, "first node has no parent")
        assertEquals(graph.nodes[0].id, graph.nodes[1].parentId)
    }

    @Test
    fun `timeline graph creates CAUSAL edge for RunStart to RunEnd`() {
        val store = EventStore()
        store.record("tl-run", RuntimeEvent.RunStart("tl-run"))
        store.record("tl-run", RuntimeEvent.RunEnd("tl-run"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-run"))

        assertNotNull(graph)
        val causalEdges = graph.edges.filter { it.type == EdgeType.CAUSAL }
        assertEquals(1, causalEdges.size)
        assertEquals(graph.nodes[0].id, causalEdges[0].fromNodeId)
        assertEquals(graph.nodes[1].id, causalEdges[0].toNodeId)
    }

    @Test
    fun `timeline graph creates CAUSAL edge for StepStart to StepEnd`() {
        val store = EventStore()
        store.record("tl-step", RuntimeEvent.RunStart("tl-step"))
        store.record("tl-step", RuntimeEvent.StepStart("s1"))
        store.record("tl-step", RuntimeEvent.StepEnd("s1"))
        store.record("tl-step", RuntimeEvent.RunEnd("tl-step"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-step"))

        assertNotNull(graph)
        val causalEdges = graph.edges.filter { it.type == EdgeType.CAUSAL }
        // 2 CAUSAL edges: RunStart→RunEnd, StepStart→StepEnd
        assertEquals(2, causalEdges.size, "should have 2 CAUSAL edges (Run + Step)")

        val stepEdge = causalEdges.find { it.fromNodeId == graph.nodes[1].id }
        assertNotNull(stepEdge, "StepStart should have a CAUSAL edge")
        assertEquals(graph.nodes[2].id, stepEdge.toNodeId, "CAUSAL edge should go to StepEnd")
    }

    @Test
    fun `timeline graph creates TOOL_FLOW edge for ToolCall to ToolResult`() {
        val store = EventStore()
        store.record("tl-tool", RuntimeEvent.RunStart("tl-tool"))
        store.record("tl-tool", RuntimeEvent.ToolCall("search", "query"))
        store.record("tl-tool", RuntimeEvent.ToolResult("search", "results"))
        store.record("tl-tool", RuntimeEvent.RunEnd("tl-tool"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-tool"))

        assertNotNull(graph)
        val toolEdges = graph.edges.filter { it.type == EdgeType.TOOL_FLOW }
        assertEquals(1, toolEdges.size, "should have 1 TOOL_FLOW edge")
        assertEquals(graph.nodes[1].id, toolEdges[0].fromNodeId, "from ToolCall")
        assertEquals(graph.nodes[2].id, toolEdges[0].toNodeId, "to ToolResult")
    }

    @Test
    fun `timeline graph handles nested steps with correct CAUSAL pairing`() {
        val store = EventStore()
        store.record("tl-nest", RuntimeEvent.RunStart("tl-nest"))
        store.record("tl-nest", RuntimeEvent.StepStart("outer"))
        store.record("tl-nest", RuntimeEvent.StepStart("inner"))
        store.record("tl-nest", RuntimeEvent.StepEnd("inner"))
        store.record("tl-nest", RuntimeEvent.StepEnd("outer"))
        store.record("tl-nest", RuntimeEvent.RunEnd("tl-nest"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-nest"))

        assertNotNull(graph)
        val causalEdges = graph.edges.filter { it.type == EdgeType.CAUSAL }
        // 3 CAUSAL edges: RunStart→RunEnd, StepStart(outer)→StepEnd(outer), StepStart(inner)→StepEnd(inner)
        assertEquals(3, causalEdges.size, "should have 3 CAUSAL edges")

        // Filter to only StepStart→StepEnd edges for nesting verification
        val stepEdges = causalEdges.filter { edge ->
            val fromNode = graph.nodes.find { it.id == edge.fromNodeId }
            fromNode?.event is RuntimeEvent.StepStart
        }
        assertEquals(2, stepEdges.size, "should have 2 Step CAUSAL edges")

        // Inner step: node_2 (StepStart inner) → node_3 (StepEnd inner)
        val innerEdge = stepEdges.find { it.fromNodeId == graph.nodes[2].id }
        assertNotNull(innerEdge, "inner StepStart should be paired")
        assertEquals(graph.nodes[3].id, innerEdge.toNodeId)

        // Outer step: node_1 (StepStart outer) → node_4 (StepEnd outer)
        val outerEdge = stepEdges.find { it.fromNodeId == graph.nodes[1].id }
        assertNotNull(outerEdge, "outer StepStart should be paired")
        assertEquals(graph.nodes[4].id, outerEdge.toNodeId)
    }

    @Test
    fun `timeline graph full DAG with all edge types`() {
        // Build a richer trace: RunStart → StepStart → ToolCall → ToolResult → StepEnd → RunEnd
        val store = EventStore()
        store.record("tl-full", RuntimeEvent.RunStart("tl-full"))
        store.record("tl-full", RuntimeEvent.StepStart("process"))
        store.record("tl-full", RuntimeEvent.ToolCall("search", "q"))
        store.record("tl-full", RuntimeEvent.ToolResult("search", "r"))
        store.record("tl-full", RuntimeEvent.MemoryChange("result", null, "r"))
        store.record("tl-full", RuntimeEvent.StepEnd("process"))
        store.record("tl-full", RuntimeEvent.RunEnd("tl-full"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-full"))

        assertNotNull(graph)
        assertEquals(7, graph.nodeCount)

        // Expected edges breakdown:
        // SEQUENTIAL: 6 edges (7 nodes → 6 sequential links)
        // CAUSAL: 2 edges (RunStart→RunEnd, StepStart→StepEnd)
        // TOOL_FLOW: 1 edge (ToolCall→ToolResult)
        assertEquals(6, graph.edges.count { it.type == EdgeType.SEQUENTIAL })
        assertEquals(2, graph.edges.count { it.type == EdgeType.CAUSAL })
        assertEquals(1, graph.edges.count { it.type == EdgeType.TOOL_FLOW })
        assertEquals(9, graph.edges.size)
    }

    @Test
    fun `filterByType preserves only matching nodes and their edges`() {
        val store = EventStore()
        store.record("tl-3", RuntimeEvent.RunStart("tl-3"))
        store.record("tl-3", RuntimeEvent.StepStart("s1"))
        store.record("tl-3", RuntimeEvent.StepEnd("s1"))
        store.record("tl-3", RuntimeEvent.RunEnd("tl-3"))

        val builder = TimelineBuilder()
        val graph = builder.build(store.getEvents("tl-3"))!!
        val filtered = builder.filterByType(
            graph,
            RuntimeEvent.RunStart::class.java,
            RuntimeEvent.RunEnd::class.java
        )

        assertEquals(2, filtered.nodeCount)
        assertTrue(filtered.nodes.all { it.event is RuntimeEvent.RunStart || it.event is RuntimeEvent.RunEnd })

        // Only edge between the two remaining Run nodes should survive
        assertEquals(1, filtered.edges.size, "filtered graph should preserve 1 SEQUENTIAL edge")
    }

    // ================================================================
    // RuntimeDebugger
    // ================================================================

    @Test
    fun `debugger timeline returns null for unknown trace`() {
        val store = EventStore()
        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        assertNull(dbg.timeline("unknown"))
    }

    @Test
    fun `debugger timeline returns graph for known trace`() {
        val store = EventStore()
        store.record("dt-1", RuntimeEvent.RunStart("dt-1"))
        store.record("dt-1", RuntimeEvent.StepStart("s1"))
        store.record("dt-1", RuntimeEvent.StepEnd("s1"))
        store.record("dt-1", RuntimeEvent.RunEnd("dt-1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)
        val graph = dbg.timeline("dt-1")

        assertNotNull(graph)
        assertEquals(4, graph.nodeCount)
        assertTrue(graph.edges.isNotEmpty(), "debugger graph should have edges")
    }

    @Test
    fun `debugger traceIds returns distinct traces`() {
        val store = EventStore()
        store.record("t1", RuntimeEvent.RunStart("t1"))
        store.record("t2", RuntimeEvent.RunStart("t2"))
        store.record("t1", RuntimeEvent.RunEnd("t1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)
        val ids = dbg.traceIds()

        assertEquals(2, ids.size)
        assertTrue(ids.containsAll(listOf("t1", "t2")))
    }

    @Test
    fun `debugger queryEvents with traceId filter`() {
        val store = EventStore()
        store.record("t1", RuntimeEvent.RunStart("t1"))
        store.record("t2", RuntimeEvent.RunStart("t2"))
        store.record("t1", RuntimeEvent.RunEnd("t1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        val t1Events = dbg.queryEvents(EventFilter(traceId = "t1"))
        assertEquals(2, t1Events.size)

        val t2Events = dbg.queryEvents(EventFilter(traceId = "t2"))
        assertEquals(1, t2Events.size)
    }

    @Test
    fun `debugger queryEvents with event type filter`() {
        val store = EventStore()
        store.record("t1", RuntimeEvent.RunStart("t1"))
        store.record("t1", RuntimeEvent.StepStart("s1"))
        store.record("t1", RuntimeEvent.StepEnd("s1"))
        store.record("t1", RuntimeEvent.RunEnd("t1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        val starts = dbg.queryEvents(EventFilter(
            eventTypes = setOf(RuntimeEvent.StepStart::class.java)
        ))
        assertEquals(1, starts.size)
    }

    @Test
    fun `debugger queryEvents with time range filter`() {
        val store = EventStore()
        val now = System.currentTimeMillis()

        store.record("t1", RuntimeEvent.RunStart("t1"))
        Thread.sleep(5)
        store.record("t1", RuntimeEvent.RunEnd("t1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        // Filter from far future — should return nothing
        val future = dbg.queryEvents(EventFilter(fromTimestamp = now + 10_000))
        assertEquals(0, future.size)

        // Filter from past — should return all
        val past = dbg.queryEvents(EventFilter(fromTimestamp = now - 10_000))
        assertEquals(2, past.size)
    }

    @Test
    fun `debugger eventCount`() {
        val store = EventStore()
        store.record("t1", RuntimeEvent.RunStart("t1"))
        store.record("t1", RuntimeEvent.RunEnd("t1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        assertEquals(2, dbg.eventCount())
    }

    @Test
    fun `debugger inspectState returns null in v0_6_0`() {
        val store = EventStore()
        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        assertNull(dbg.inspectState(42))
    }

    // ================================================================
    // Integration: KAgentRuntime debugger() access
    // ================================================================

    @Test
    fun `runtime debugger access works after execute`() = runTest {
        val runtime = kAgentRuntime(id = "dbg-integration") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                }
            }
        }

        val resp = runtime.execute(AgentRequest(input = "test", traceId = "dbg-int-1"))
        val dbg = runtime.debugger()

        assertNotNull(dbg)
        assertEquals("done: test", resp.output)

        val graph = dbg.timeline("dbg-int-1")
        assertNotNull(graph)
        assertTrue(graph.nodeCount >= 3) // RunStart, StepStart, StepEnd, RunEnd at minimum
        assertTrue(graph.edges.any { it.type == EdgeType.SEQUENTIAL })
        assertTrue(graph.edges.any { it.type == EdgeType.CAUSAL })
    }

    @Test
    fun `debugger sees events from multiple executions`() = runTest {
        val runtime = kAgentRuntime(id = "dbg-multi") {
            run {
                step("process") {
                    setState("output", getState("input"))
                }
            }
        }

        runtime.execute(AgentRequest(input = "a", traceId = "m-1"))
        runtime.execute(AgentRequest(input = "b", traceId = "m-2"))

        val dbg = runtime.debugger()
        val ids = dbg.traceIds()

        assertTrue(ids.contains("m-1"))
        assertTrue(ids.contains("m-2"))
        assertTrue(dbg.eventCount() >= 6) // 2 traces × 3+ events each
    }

    @Test
    fun `debugger graph has correct causal structure from real execution`() = runTest {
        val runtime = kAgentRuntime(id = "dbg-causal") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("result", "processed: $input")
                }
            }
        }

        runtime.execute(AgentRequest(input = "test", traceId = "causal-test"))
        val dbg = runtime.debugger()
        val graph = dbg.timeline("causal-test")

        assertNotNull(graph)
        assertTrue(graph.nodeCount >= 4) // RunStart, StepStart, MemoryChange, StepEnd, RunEnd

        // Verify RunStart→RunEnd CAUSAL edge exists
        val runEdge = graph.edges.find { edge ->
            edge.type == EdgeType.CAUSAL &&
                graph.nodes.any { it.id == edge.fromNodeId && it.event is RuntimeEvent.RunStart }
        }
        assertNotNull(runEdge, "RunStart->RunEnd CAUSAL edge should exist")

        // Verify SEQUENTIAL edges form a complete chain
        val sequentialEdges = graph.edges.filter { it.type == EdgeType.SEQUENTIAL }
        assertEquals(graph.nodeCount - 1, sequentialEdges.size,
            "SEQUENTIAL edges should link all nodes in order")
    }

    // ================================================================
    // TimelineProjection
    // ================================================================

    @Test
    fun `projection builds indices with byStepId`() {
        val store = EventStore()
        store.record("proj", RuntimeEvent.RunStart("proj"))
        store.record("proj", RuntimeEvent.StepStart("compute"))
        store.record("proj", RuntimeEvent.StepEnd("compute"))
        store.record("proj", RuntimeEvent.StepStart("validate"))
        store.record("proj", RuntimeEvent.StepEnd("validate"))
        store.record("proj", RuntimeEvent.RunEnd("proj"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("proj"))

        assertNotNull(graph)
        val engine = TimelineQueryEngine(graph)

        // byStep finds StepStart and StepEnd nodes
        val computeNodes = engine.byStep("compute")
        assertEquals(2, computeNodes.size)
        assertTrue(computeNodes.any { it.event is RuntimeEvent.StepStart })
        assertTrue(computeNodes.any { it.event is RuntimeEvent.StepEnd })

        val validateNodes = engine.byStep("validate")
        assertEquals(2, validateNodes.size)
    }

    @Test
    fun `projection builds indices with byTool`() {
        val store = EventStore()
        store.record("proj", RuntimeEvent.RunStart("proj"))
        store.record("proj", RuntimeEvent.ToolCall("search", "q1"))
        store.record("proj", RuntimeEvent.ToolResult("search", "r1"))
        store.record("proj", RuntimeEvent.ToolCall("compute", "42"))
        store.record("proj", RuntimeEvent.ToolResult("compute", "84"))
        store.record("proj", RuntimeEvent.RunEnd("proj"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("proj"))

        assertNotNull(graph)
        val engine = TimelineQueryEngine(graph)

        val searchNodes = engine.byTool("search")
        assertEquals(2, searchNodes.size)

        val computeNodes = engine.byTool("compute")
        assertEquals(2, computeNodes.size)
    }

    @Test
    fun `projection builds adjacency indices`() {
        val store = EventStore()
        store.record("proj", RuntimeEvent.RunStart("proj"))
        store.record("proj", RuntimeEvent.StepStart("s1"))
        store.record("proj", RuntimeEvent.StepEnd("s1"))
        store.record("proj", RuntimeEvent.RunEnd("proj"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("proj"))

        assertNotNull(graph)
        val engine = TimelineQueryEngine(graph)

        // node_0 (RunStart) should have node_1 (StepStart) as child (SEQUENTIAL)
        val rootChildren = engine.children(graph.nodes[0].id)
        assertTrue(rootChildren.isNotEmpty())
        assertEquals(graph.nodes[1].id, rootChildren[0].id)

        // node_2 (StepEnd) should have node_3 (RunEnd) as child (SEQUENTIAL)
        val lastNodeParents = engine.parents(graph.nodes[3].id)
        assertTrue(lastNodeParents.isNotEmpty())
        assertEquals(graph.nodes[2].id, lastNodeParents[0].id)
    }

    // ================================================================
    // TimelineQueryEngine
    // ================================================================

    @Test
    fun `query engine byTimeRange returns filtered nodes`() {
        val store = EventStore()
        val now = System.currentTimeMillis()

        store.record("tr", RuntimeEvent.RunStart("tr"))
        Thread.sleep(2)
        store.record("tr", RuntimeEvent.StepStart("s1"))
        Thread.sleep(2)
        store.record("tr", RuntimeEvent.StepEnd("s1"))
        store.record("tr", RuntimeEvent.RunEnd("tr"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("tr"))!!
        val engine = TimelineQueryEngine(graph)

        // Narrow range — only first node
        val narrow = engine.byTimeRange(now - 1, now + 1)
        assertTrue(narrow.isNotEmpty())

        // Full range — all nodes
        val all = engine.byTimeRange(0, Long.MAX_VALUE)
        assertEquals(graph.nodeCount, all.size)
    }

    @Test
    fun `query engine descendants and ancestors`() {
        val store = EventStore()
        store.record("ga", RuntimeEvent.RunStart("ga"))
        store.record("ga", RuntimeEvent.StepStart("outer"))
        store.record("ga", RuntimeEvent.StepStart("inner"))
        store.record("ga", RuntimeEvent.StepEnd("inner"))
        store.record("ga", RuntimeEvent.StepEnd("outer"))
        store.record("ga", RuntimeEvent.RunEnd("ga"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("ga"))!!
        val engine = TimelineQueryEngine(graph)

        // Root (RunStart) should have all others as descendants
        val rootDescendants = engine.descendants(graph.nodes[0].id)
        assertEquals(graph.nodeCount - 1, rootDescendants.size)

        // Last node (RunEnd) should have all others as ancestors
        val lastAncestors = engine.ancestors(graph.nodes.last().id)
        assertEquals(graph.nodeCount - 1, lastAncestors.size)
    }

    @Test
    fun `query engine critical path returns longest execution chain`() {
        val store = EventStore()
        store.record("cp", RuntimeEvent.RunStart("cp"))
        store.record("cp", RuntimeEvent.StepStart("slow"))
        store.record("cp", RuntimeEvent.ToolCall("api", "req"))
        store.record("cp", RuntimeEvent.ToolResult("api", "res"))
        store.record("cp", RuntimeEvent.StepEnd("slow"))
        store.record("cp", RuntimeEvent.RunEnd("cp"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("cp"))!!
        val engine = TimelineQueryEngine(graph)

        val critical = engine.criticalPath()
        assertTrue(critical.isNotEmpty())
        // Critical path should start with the first node
        assertEquals(graph.nodes[0].id, critical.first().id)
    }

    @Test
    fun `query engine filterByType`() {
        val store = EventStore()
        store.record("ft", RuntimeEvent.RunStart("ft"))
        store.record("ft", RuntimeEvent.StepStart("s1"))
        store.record("ft", RuntimeEvent.MemoryChange("k", null, "v"))
        store.record("ft", RuntimeEvent.StepEnd("s1"))
        store.record("ft", RuntimeEvent.RunEnd("ft"))

        val projection = TimelineProjection()
        val graph = projection.project(store.getEvents("ft"))!!
        val engine = TimelineQueryEngine(graph)

        val filtered = engine.filterByType(
            RuntimeEvent.RunStart::class.java,
            RuntimeEvent.RunEnd::class.java
        )

        assertEquals(2, filtered.nodeCount)
        assertTrue(filtered.nodes.all { it.event is RuntimeEvent.RunStart || it.event is RuntimeEvent.RunEnd })
    }

    // ================================================================
    // RuntimeDebugger v0.6.2 API
    // ================================================================

    @Test
    fun `debugger query returns engine for known trace`() {
        val store = EventStore()
        store.record("q1", RuntimeEvent.RunStart("q1"))
        store.record("q1", RuntimeEvent.StepStart("s1"))
        store.record("q1", RuntimeEvent.StepEnd("s1"))
        store.record("q1", RuntimeEvent.RunEnd("q1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)
        val engine = dbg.query("q1")

        assertNotNull(engine)
        assertEquals(4, engine.byStep("s1").size + 2) // step + run events
    }

    @Test
    fun `debugger inspect returns correct node`() {
        val store = EventStore()
        store.record("ins", RuntimeEvent.RunStart("ins"))
        store.record("ins", RuntimeEvent.StepStart("s1"))
        store.record("ins", RuntimeEvent.StepEnd("s1"))
        store.record("ins", RuntimeEvent.RunEnd("ins"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        // Trigger graph cache
        dbg.timeline("ins")

        val node = dbg.inspect("node_1_ins")
        assertNotNull(node)
        assertTrue(node.event is RuntimeEvent.StepStart)
    }

    @Test
    fun `debugger children and parents`() {
        val store = EventStore()
        store.record("nav", RuntimeEvent.RunStart("nav"))
        store.record("nav", RuntimeEvent.StepStart("s1"))
        store.record("nav", RuntimeEvent.StepEnd("s1"))
        store.record("nav", RuntimeEvent.RunEnd("nav"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        // Trigger cache
        dbg.timeline("nav")

        // First node has two children (SEQUENTIAL + CAUSAL to RunEnd)
        val firstChildren = dbg.children("node_0_nav")
        assertEquals(2, firstChildren.size)
        assertTrue(firstChildren.any { it.id == "node_1_nav" })
        assertTrue(firstChildren.any { it.id == "node_3_nav" })

        // Middle node has one parent and one child
        val midParents = dbg.parents("node_1_nav")
        assertEquals(1, midParents.size)
        assertEquals("node_0_nav", midParents[0].id)

        val midChildren = dbg.children("node_1_nav")
        assertEquals(1, midChildren.size)
        assertEquals("node_2_nav", midChildren[0].id)

        // Last node (RunEnd) has two parents: SEQUENTIAL + CAUSAL
        val lastParents = dbg.parents("node_3_nav")
        assertEquals(2, lastParents.size)
        assertTrue(lastParents.any { it.id == "node_2_nav" })
        assertTrue(lastParents.any { it.id == "node_0_nav" })
    }

    @Test
    fun `debugger query returns null for unknown trace`() {
        val store = EventStore()
        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)

        assertNull(dbg.query("unknown"))
        assertNull(dbg.inspect("node_0_unknown"))
        assertTrue(dbg.children("node_0_unknown").isEmpty())
        assertTrue(dbg.parents("node_0_unknown").isEmpty())
    }
}
