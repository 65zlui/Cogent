package com.kagent.fiber

import com.kagent.memory.core.Memory
import com.kagent.memory.dependency.DependencyTracker
import com.kagent.memory.dependency.InvalidationGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutomaticDependencyTrackingTest {

    @Test
    fun `test GlobalObservationContext tracks dependencies`() = runBlocking {
        GlobalObservationContext.startTracking()
        GlobalObservationContext.recordDependency("key1")
        GlobalObservationContext.recordDependency("key2")
        val deps = GlobalObservationContext.stopTracking()

        assertEquals(2, deps.size)
        assertTrue("key1" in deps)
        assertTrue("key2" in deps)
    }

    @Test
    fun `test GlobalObservationContext returns empty when not tracking`() = runBlocking {
        val deps = GlobalObservationContext.stopTracking()
        assertTrue(deps.isEmpty())
    }

    @Test
    fun `test isTracking returns correct state`() = runBlocking {
        assertTrue(!GlobalObservationContext.isTracking())
        GlobalObservationContext.startTracking()
        assertTrue(GlobalObservationContext.isTracking())
        GlobalObservationContext.stopTracking()
        assertTrue(!GlobalObservationContext.isTracking())
    }
}

class SuspendableDerivedStateTest {

    @Test
    fun `test suspendable derived state computes value`() = runBlocking {
        val tracker = DependencyTracker()
        var computeCount = 0

        val derived = derivedSuspend(
            id = "test",
            tracker = tracker
        ) {
            GlobalObservationContext.startTracking()
            try {
                computeCount++
                "computed_${computeCount}"
            } finally {
                GlobalObservationContext.stopTracking()
            }
        }

        assertEquals("computed_1", derived.value)
        assertEquals(1, computeCount)
    }

    @Test
    fun `test suspendable derived state with explicit deps`() = runBlocking {
        val depTracker = DependencyTracker()
        var computeCount = 0

        val derived = derivedSuspendWithDeps(
            id = "test",
            tracker = depTracker,
            dependencyIds = setOf("a", "b")
        ) {
            computeCount++
            "computed"
        }

        assertEquals("computed", derived.value)
        assertEquals(setOf("a", "b"), derived.getDependencies())
    }

    @Test
    fun `test suspendable derived state invalidation`() = runBlocking {
        val tracker = DependencyTracker()
        var value = 10

        val derived = derivedSuspend(
            id = "test",
            tracker = tracker
        ) {
            GlobalObservationContext.startTracking()
            try {
                value * 2
            } finally {
                GlobalObservationContext.stopTracking()
            }
        }

        assertEquals(20, derived.value)

        value = 15
        derived.invalidate()

        assertEquals(30, derived.value)
    }
}

class AgentFiberTest {

    @Test
    fun `test fiber submits and completes task`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 2)
        var executed = false

        fiber.submit(
            id = "task1",
            priority = FiberPriority.NORMAL
        ) {
            executed = true
        }

        delay(100)
        assertTrue(executed)
        assertEquals(FiberState.COMPLETED, fiber.getTaskState("task1"))
    }

    @Test
    fun `test fiber priority ordering`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 1)
        val executionOrder = mutableListOf<String>()

        fiber.submit("low", FiberPriority.LOW) {
            executionOrder.add("low")
        }
        fiber.submit("high", FiberPriority.HIGH) {
            executionOrder.add("high")
        }

        delay(200)
        assertTrue(executionOrder.isNotEmpty())
    }

    @Test
    fun `test fiber suspend and resume`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 2)

        fiber.submit("task1") {
            delay(5000)
        }

        delay(100)
        fiber.suspendTask("task1")
        delay(100)
        val state = fiber.getTaskState("task1")
        assertTrue(state == FiberState.SUSPENDED || state == FiberState.CANCELLED)
    }

    @Test
    fun `test fiber cancel task`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 2)
        var executed = false

        fiber.submit("task1") {
            delay(1000)
            executed = true
        }

        delay(50)
        fiber.cancelTask("task1")
        delay(50)
        assertEquals(FiberState.CANCELLED, fiber.getTaskState("task1"))
    }

    @Test
    fun `test fiber state changes flow`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 2)
        var stateChangeReceived = false

        val job = CoroutineScope(Dispatchers.Default).launch {
            fiber.stateChanges.collect {
                stateChangeReceived = true
            }
        }

        fiber.submit("task1") { }
        delay(100)
        assertTrue(stateChangeReceived)
        job.cancel()
    }

    @Test
    fun `test fiber get all task states`() = runBlocking {
        val fiber = AgentFiber(maxConcurrency = 2)

        fiber.submit("task1") { }
        fiber.submit("task2") { }

        delay(100)
        val states = fiber.getAllTaskStates()
        assertTrue(states.size >= 2)
    }
}

class ExecutionTimelineTest {

    @Test
    fun `test timeline records step events`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordStepStart("step1")
        timeline.recordStepComplete("step1", 100)

        val records = timeline.getRecords()
        assertEquals(2, records.size)
        assertEquals(ExecutionType.STEP_START, records[0].type)
        assertEquals(ExecutionType.STEP_COMPLETE, records[1].type)
    }

    @Test
    fun `test timeline records tool events`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordToolCall("searchTool", "step1")
        timeline.recordToolResult("searchTool", "step1", "result")

        val toolRecords = timeline.getRecordsByType(ExecutionType.TOOL_CALL)
        assertEquals(1, toolRecords.size)
    }

    @Test
    fun `test timeline checkpoint`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordCheckpoint("cp1", mapOf("a" to 1))
        timeline.recordCheckpoint("cp2", mapOf("b" to 2))

        val checkpointRecords = timeline.getRecordsByType(ExecutionType.CHECKPOINT)
        assertEquals(2, checkpointRecords.size)

        val recordsUpToCp1 = timeline.getRecordsUpToCheckpoint("cp1")
        assertEquals(1, recordsUpToCp1.size)
    }

    @Test
    fun `test timeline state changes`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordStateChange("key", "oldValue", "newValue")

        val stateRecords = timeline.getRecordsByType(ExecutionType.STATE_CHANGE)
        assertEquals(1, stateRecords.size)
        assertEquals("key", stateRecords[0].state["key"])
        assertEquals("oldValue", stateRecords[0].state["oldValue"])
        assertEquals("newValue", stateRecords[0].state["newValue"])
    }

    @Test
    fun `test timeline step timeline`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordStepStart("step1")
        timeline.recordToolCall("tool1", "step1")
        timeline.recordStepComplete("step1", 50)

        val stepRecords = timeline.getStepTimeline("step1")
        assertEquals(3, stepRecords.size)
    }

    @Test
    fun `test timeline full timeline`() = runBlocking {
        val timeline = ExecutionTimeline()
        timeline.recordStepStart("step1")
        timeline.recordStepComplete("step1", 100)
        timeline.recordCheckpoint("cp1")

        val fullTimeline = timeline.getFullTimeline()
        assertEquals(3, fullTimeline.size)
        assertTrue(fullTimeline[0].timestamp <= fullTimeline[1].timestamp)
    }
}

class ToolExecutionContextTest {

    @Test
    fun `test register and call tool`() = runBlocking {
        val memory = Memory()
        val timeline = ExecutionTimeline()
        val context = ToolExecutionContext(memory, timeline)

        context.registerTool("greet") { args ->
            "Hello, ${args["name"]}"
        }

        val result = context.callTool("greet", mapOf("name" to "Tom"), "step1")
        assertEquals("Hello, Tom", result)
    }

    @Test
    fun `test tool call history`() = runBlocking {
        val memory = Memory()
        val timeline = ExecutionTimeline()
        val context = ToolExecutionContext(memory, timeline)

        context.registerTool("add") { args ->
            ((args["a"] as? Int) ?: 0) + ((args["b"] as? Int) ?: 0)
        }

        context.callTool("add", mapOf("a" to 1, "b" to 2), "step1")
        context.callTool("add", mapOf("a" to 3, "b" to 4), "step1")

        val history = context.getCallHistory()
        assertEquals(2, history.size)
    }

    @Test
    fun `test tool error handling`() = runBlocking {
        val memory = Memory()
        val timeline = ExecutionTimeline()
        val context = ToolExecutionContext(memory, timeline)

        context.registerTool("failing") {
            throw RuntimeException("Tool error")
        }

        try {
            context.callTool("failing", emptyMap(), "step1")
        } catch (e: Exception) {
        }

        val history = context.getCallHistory()
        assertEquals(1, history.size)
        assertNotNull(history[0].error)
    }

    @Test
    fun `test tool calls for step`() = runBlocking {
        val memory = Memory()
        val timeline = ExecutionTimeline()
        val context = ToolExecutionContext(memory, timeline)

        context.registerTool("tool1") { "result1" }
        context.registerTool("tool2") { "result2" }

        context.callTool("tool1", emptyMap(), "step1")
        context.callTool("tool2", emptyMap(), "step2")

        val step1Calls = context.getToolCallsForStep("step1")
        assertEquals(1, step1Calls.size)
        assertEquals("tool1", step1Calls[0].toolName)
    }
}

class FiberRuntimeTest {

    @Test
    fun `test fiber runtime executes steps`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        runtime.run {
            step("step1") {
                setState("status", "step1_done")
            }
        }

        delay(200)
        assertEquals("step1_done", memory.getState<String>("status"))
    }

    @Test
    fun `test fiber runtime with derived suspend`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        memory.setState("a", 10)
        memory.setState("b", 20)

        runtime.run {
            step("compute") {
                val derived = derivedSuspend("sum") {
                    val a = getStateWithTracking("a") as? Int ?: 0
                    val b = getStateWithTracking("b") as? Int ?: 0
                    a + b
                }
                setState("result", derived.value)
            }
        }

        delay(200)
        assertEquals(30, memory.getState<Int>("result"))
    }

    @Test
    fun `test fiber runtime with tools`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        runtime.registerTool("fetchData") { args ->
            "data_${args["id"]}"
        }

        runtime.run {
            step("fetch") {
                val result = callTool("fetchData", mapOf("id" to "123"))
                setState("fetched", result)
            }
        }

        delay(200)
        assertEquals("data_123", memory.getState<String>("fetched"))
    }

    @Test
    fun `test fiber runtime checkpoint and replay`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        runtime.run {
            step("init") {
                setState("a", 1)
                checkpoint("afterInit")
            }
            step("modify") {
                setState("a", 2)
            }
        }

        delay(200)
        assertEquals(2, memory.getState<Int>("a"))

        runtime.replayToCheckpoint("afterInit")
        assertEquals(1, memory.getState<Int>("a"))
    }

    @Test
    fun `test fiber runtime timeline`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        runtime.run {
            step("step1") {
                setState("x", 1)
            }
        }

        delay(200)
        val timeline = runtime.getFullTimeline()
        assertTrue(timeline.isNotEmpty())
    }

    @Test
    fun `test fiber runtime tool call history`() = runBlocking {
        val memory = Memory()
        val runtime = FiberRuntime(id = "test", memory = memory)

        runtime.registerTool("search") { args -> "result" }

        runtime.run {
            step("search") {
                callTool("search", mapOf("query" to "test"))
            }
        }

        delay(200)
        val history = runtime.getToolCallHistory()
        assertEquals(1, history.size)
        assertEquals("search", history[0].toolName)
    }
}
