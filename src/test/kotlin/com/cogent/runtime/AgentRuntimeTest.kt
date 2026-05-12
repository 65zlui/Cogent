package com.cogent.runtime

import com.cogent.memory.core.Memory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class AgentRuntimeTest {

    @Test
    fun `test runtime creates successfully`() = runBlocking {
        val memory = Memory()
        val runtime = AgentRuntime(id = "test", memory = memory)
        
        assertEquals("test", runtime.id)
        assertEquals(AgentState.Idle, runtime.state)
    }

    @Test
    fun `test step scheduler works`() = runBlocking {
        val scheduler = StepScheduler()
        
        scheduler.addStep("step1")
        scheduler.startStep("step1")
        scheduler.completeStep("step1")
        
        val completed = scheduler.getCompletedSteps()
        assertEquals(1, completed.size)
        assertEquals("step1", completed[0].name)
    }

    @Test
    fun `test trace records events`() = runBlocking {
        val memory = Memory()
        val runtime = AgentRuntime(id = "test", memory = memory)
        
        runtime.getTrace().recordEvent("test.event")
        
        val events = runtime.getTrace().getEvents()
        assertEquals(1, events.size)
        assertEquals("test.event", events[0].type)
    }

    @Test
    fun `test runtime can run agent`() = runBlocking {
        val memory = Memory()
        val runtime = AgentRuntime(id = "test", memory = memory)
        
        runtime.run {
            step("testStep") {
                setState("status", "completed")
            }
        }
        
        delay(300)
        assertEquals("completed", memory.getState<String>("status"))
    }

    @Test
    fun `test tool execution works`() = runBlocking {
        val memory = Memory()
        val runtime = AgentRuntime(id = "test", memory = memory)
        
        runtime.run {
            step("withTool") {
                val result = tool("myTool") {
                    "toolResult"
                }
                setState("result", result)
            }
        }
        
        delay(300)
        assertEquals("toolResult", memory.getState<String>("result"))
    }

    @Test
    fun `test checkpoint creation`() = runBlocking {
        val memory = Memory()
        val runtime = AgentRuntime(id = "test", memory = memory)
        
        runtime.run {
            setState("a", 1)
            checkpoint("testCheckpoint")
        }
        
        delay(300)
        val checkpoints = runtime.getTrace().getCheckpoints()
        assertTrue(checkpoints.size >= 1)
        assertEquals("testCheckpoint", checkpoints[0].name)
    }

    @Test
    fun `test step fail works`() = runBlocking {
        val scheduler = StepScheduler()
        
        scheduler.addStep("step1")
        scheduler.startStep("step1")
        scheduler.failStep("step1", "Error message")
        
        val steps = scheduler.getSteps()
        assertEquals(1, steps.size)
        assertEquals(StepStatus.FAILED, steps[0].status)
        assertEquals("Error message", steps[0].metadata["error"])
    }

    @Test
    fun `test reset works`() = runBlocking {
        val scheduler = StepScheduler()
        
        scheduler.addStep("step1")
        scheduler.reset()
        
        assertEquals(0, scheduler.size())
    }
}
