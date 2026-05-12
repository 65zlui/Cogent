package com.kagent.fiber.scheduler

import com.kagent.memory.core.Memory
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AgentSchedulerTest {

    @Test
    fun `test schedule task`() = runTest {
        var executed = false
        val scheduler = AgentScheduler(maxConcurrency = 2, scope = this)

        scheduler.schedule(
            id = "task1",
            type = TaskType.STEP_EXECUTE
        ) {
            executed = true
        }

        advanceUntilIdle()
        assertTrue(executed)

        scheduler.shutdown()
    }

    @Test
    fun `test schedule multiple tasks`() = runTest {
        val scheduler = AgentScheduler(maxConcurrency = 2, scope = this)

        val executionOrder = mutableListOf<String>()

        scheduler.schedule("task1", TaskType.STEP_EXECUTE, priority = 10) {
            executionOrder.add("task1")
        }
        scheduler.schedule("task2", TaskType.STEP_EXECUTE, priority = 20) {
            executionOrder.add("task2")
        }

        advanceUntilIdle()
        assertEquals(2, executionOrder.size)

        scheduler.shutdown()
    }

    @Test
    fun `test get all tasks`() = runTest {
        val scheduler = AgentScheduler(maxConcurrency = 2, scope = this)

        scheduler.schedule("task1", TaskType.STEP_EXECUTE) { }
        scheduler.schedule("task2", TaskType.STEP_EXECUTE) { }

        advanceUntilIdle()
        val tasks = scheduler.getAllTasks()
        assertTrue(tasks.size >= 2)

        scheduler.shutdown()
    }

    @Test
    fun `test events flow`() = runTest {
        val scheduler = AgentScheduler(maxConcurrency = 2, scope = this)

        val events = mutableListOf<SchedulerEvent>()
        val job = launch {
            scheduler.events.collect { event ->
                events.add(event)
            }
        }

        scheduler.schedule("task1", TaskType.STEP_EXECUTE) { }
        advanceUntilIdle()

        assertTrue(events.isNotEmpty())
        job.cancel()
        scheduler.shutdown()
    }
}

class RuntimeHeartTest {

    @Test
    fun `test runtime heart executes steps`() = runTest {
        val memory = Memory()
        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("step1") {
                setState("status", "step1_done")
            }
        }

        assertEquals("step1_done", memory.getState<String>("status"))
    }

    @Test
    fun `test runtime heart with derived state`() = runTest {
        val memory = Memory()
        memory.setState("a", 10)
        memory.setState("b", 20)

        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("compute") {
                registerDerived("sum", setOf("a", "b")) {
                    val a = getStateWithTracking("a") as? Int ?: 0
                    val b = getStateWithTracking("b") as? Int ?: 0
                    a + b
                }
            }
        }

        val history = heart.getDerivedRecomputes()
        assertTrue(history.isNotEmpty())
    }

    @Test
    fun `test runtime heart checkpoint`() = runTest {
        val memory = Memory()
        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("init") {
                setState("a", 1)
                checkpoint("cp1")
            }
        }

        val checkpoints = heart.getCheckpoints()
        assertTrue(checkpoints.isNotEmpty())
        assertEquals("cp1", checkpoints[0].metadata["name"])
    }

    @Test
    fun `test runtime heart replay to checkpoint`() = runTest {
        val memory = Memory()
        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("init") {
                setState("a", 1)
                checkpoint("cp1")
            }
            step("modify") {
                setState("a", 2)
            }
        }

        assertEquals(2, memory.getState<Int>("a"))

        heart.replayToCheckpoint("cp1")
        assertEquals(1, memory.getState<Int>("a"))
    }

    @Test
    fun `test runtime heart state history`() = runTest {
        val memory = Memory()
        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("step1") {
                setState("x", 1)
                setState("y", 2)
            }
        }

        val history = heart.getStateHistory()
        assertTrue(history.size >= 3)
    }

    @Test
    fun `test runtime heart state changes`() = runTest {
        val memory = Memory()
        val heart = RuntimeHeart(id = "test", memory = memory)

        heart.run {
            step("step1") {
                setState("key1", "value1")
            }
        }

        val changes = heart.getStateChanges()
        assertTrue(changes.isNotEmpty())
    }
}
