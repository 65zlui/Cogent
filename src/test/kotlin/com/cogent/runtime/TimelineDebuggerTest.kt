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
    // TimelineBuilder
    // ================================================================

    @Test
    fun `timeline builder returns null for empty events`() {
        val builder = TimelineBuilder()
        assertNull(builder.build(emptyList()))
    }

    @Test
    fun `timeline builder creates nodes with parent links`() {
        val store = EventStore()
        store.record("tl-1", RuntimeEvent.RunStart("tl-1"))
        store.record("tl-1", RuntimeEvent.StepStart("s1"))
        store.record("tl-1", RuntimeEvent.StepEnd("s1"))
        store.record("tl-1", RuntimeEvent.RunEnd("tl-1"))

        val builder = TimelineBuilder()
        val timeline = builder.build(store.getEvents("tl-1"))

        assertNotNull(timeline)
        assertEquals("tl-1", timeline.traceId)
        assertEquals(4, timeline.nodeCount)
        assertNull(timeline.nodes[0].parentId, "first node has no parent")
        assertNotNull(timeline.nodes[1].parentId, "second node has parent")
        assertEquals(timeline.nodes[0].id, timeline.nodes[1].parentId)
    }

    @Test
    fun `timeline builder start and end times`() {
        val store = EventStore()
        store.record("tl-2", RuntimeEvent.RunStart("tl-2"))
        store.record("tl-2", RuntimeEvent.RunEnd("tl-2"))

        val builder = TimelineBuilder()
        val timeline = builder.build(store.getEvents("tl-2"))

        assertNotNull(timeline)
        assertTrue(timeline.endTime >= timeline.startTime)
    }

    @Test
    fun `timeline builder filterByType`() {
        val store = EventStore()
        store.record("tl-3", RuntimeEvent.RunStart("tl-3"))
        store.record("tl-3", RuntimeEvent.StepStart("s1"))
        store.record("tl-3", RuntimeEvent.StepEnd("s1"))
        store.record("tl-3", RuntimeEvent.RunEnd("tl-3"))

        val builder = TimelineBuilder()
        val timeline = builder.build(store.getEvents("tl-3"))!!
        val filtered = builder.filterByType(timeline, RuntimeEvent.RunStart::class.java, RuntimeEvent.RunEnd::class.java)

        assertEquals(2, filtered.nodeCount)
        assertTrue(filtered.nodes.all { it.event is RuntimeEvent.RunStart || it.event is RuntimeEvent.RunEnd })
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
    fun `debugger timeline returns timeline for known trace`() {
        val store = EventStore()
        store.record("dt-1", RuntimeEvent.RunStart("dt-1"))
        store.record("dt-1", RuntimeEvent.StepStart("s1"))
        store.record("dt-1", RuntimeEvent.StepEnd("s1"))
        store.record("dt-1", RuntimeEvent.RunEnd("dt-1"))

        val memory = com.cogent.memory.core.Memory()
        val dbg = RuntimeDebugger(store, memory)
        val timeline = dbg.timeline("dt-1")

        assertNotNull(timeline)
        assertEquals(4, timeline.nodeCount)
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

        val timeline = dbg.timeline("dbg-int-1")
        assertNotNull(timeline)
        assertTrue(timeline.nodeCount >= 3) // RunStart, StepStart, StepEnd, RunEnd at minimum
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
}
