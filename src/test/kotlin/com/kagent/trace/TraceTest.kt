package com.kagent.trace

import com.kagent.memory.core.Memory
import com.kagent.memory.snapshot.DefaultMemorySnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class ExecutionTraceTest {

    @Test
    fun `test record event`() = runTest {
        val trace = ExecutionTrace()
        trace.recordEvent("test.event", mapOf("key" to "value"))
        
        assertEquals(1, trace.getEventCount())
        val events = trace.getEvents()
        assertEquals("test.event", events[0].type)
        assertEquals("value", events[0].metadata["key"])
    }

    @Test
    fun `test record checkpoint`() = runTest {
        val trace = ExecutionTrace()
        val snapshot = DefaultMemorySnapshot(
            timestamp = System.currentTimeMillis(),
            states = mapOf("a" to 1)
        )
        trace.recordCheckpoint("checkpoint1", snapshot)
        
        assertEquals(1, trace.getCheckpointCount())
        val checkpoints = trace.getCheckpoints()
        assertEquals("checkpoint1", checkpoints[0].name)
    }

    @Test
    fun `test record invalidation`() = runTest {
        val trace = ExecutionTrace()
        trace.recordInvalidation("source", setOf("affected1", "affected2"))
        
        val invalidations = trace.getInvalidations()
        assertEquals(1, invalidations.size)
        assertEquals("source", invalidations[0].sourceId)
        assertEquals(2, invalidations[0].affectedNodes.size)
    }

    @Test
    fun `test get timeline`() = runTest {
        val trace = ExecutionTrace()
        trace.recordEvent("event1")
        trace.recordEvent("event2")
        
        val timeline = trace.getTimeline()
        assertEquals(2, timeline.size)
    }

    @Test
    fun `test get events by type`() = runTest {
        val trace = ExecutionTrace()
        trace.recordEvent("type1")
        trace.recordEvent("type2")
        trace.recordEvent("type1")
        
        val events = trace.getEventsByType("type1")
        assertEquals(2, events.size)
    }

    @Test
    fun `test clear`() = runTest {
        val trace = ExecutionTrace()
        trace.recordEvent("event1")
        trace.clear()
        
        assertEquals(0, trace.getEventCount())
    }
}

class ReplayEngineTest {

    @Test
    fun `test replay to checkpoint`() = runTest {
        val memory = Memory()
        memory.setState("a", 1)
        
        val trace = ExecutionTrace()
        val snapshot = memory.snapshot()
        trace.recordCheckpoint("cp1", snapshot)
        
        memory.setState("a", 2)
        
        val replay = ReplayEngine(trace, memory)
        replay.replayToCheckpoint("cp1")
        
        assertEquals(1, memory.getState<Int>("a"))
    }

    @Test
    fun `test replay all events`() = runTest {
        val memory = Memory()
        val trace = ExecutionTrace()
        trace.recordEvent("event1")
        trace.recordEvent("event2")
        
        val replay = ReplayEngine(trace, memory)
        val events = replay.replayAll()
        
        assertEquals(2, events.size)
    }

    @Test
    fun `test get timeline from replay engine`() = runTest {
        val memory = Memory()
        val trace = ExecutionTrace()
        trace.recordEvent("event1")
        
        val replay = ReplayEngine(trace, memory)
        val timeline = replay.getTimeline()
        
        assertTrue(timeline.isNotEmpty())
    }

    @Test
    fun `test get checkpoint names`() = runTest {
        val memory = Memory()
        val trace = ExecutionTrace()
        val snapshot = memory.snapshot()
        trace.recordCheckpoint("cp1", snapshot)
        trace.recordCheckpoint("cp2", snapshot)
        
        val replay = ReplayEngine(trace, memory)
        val names = replay.getCheckpointNames()
        
        assertEquals(2, names.size)
        assertTrue("cp1" in names)
        assertTrue("cp2" in names)
    }

    @Test
    fun `test get latest checkpoint`() = runTest {
        val memory = Memory()
        val trace = ExecutionTrace()
        val snapshot1 = memory.snapshot()
        trace.recordCheckpoint("cp1", snapshot1)
        
        memory.setState("a", 1)
        val snapshot2 = memory.snapshot()
        trace.recordCheckpoint("cp2", snapshot2)
        
        val replay = ReplayEngine(trace, memory)
        val latest = replay.getLatestCheckpoint()
        
        assertNotNull(latest)
        assertEquals("cp2", latest.name)
    }
}
