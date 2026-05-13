package com.cogent.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KAgentRuntimeTest {

    @Test
    fun `test create and run runtime`() = runTest {
        val runtime = kAgentRuntime(id = "test1") {
            step("init") {
                setState("status", "init_done")
            }
        }

        assertEquals(RuntimeState.Completed, runtime.state)
        assertEquals("init_done", runtime.getState<String>("status"))
    }

    @Test
    fun `test multiple steps`() = runTest {
        val runtime = kAgentRuntime(id = "test2") {
            step("step1") {
                setState("a", 1)
            }
            step("step2") {
                setState("b", 2)
            }
            step("step3") {
                setState("c", 3)
            }
        }

        assertEquals(RuntimeState.Completed, runtime.state)
        assertEquals(1, runtime.getState<Int>("a"))
        assertEquals(2, runtime.getState<Int>("b"))
        assertEquals(3, runtime.getState<Int>("c"))
    }

    @Test
    fun `test state changes`() = runTest {
        val runtime = kAgentRuntime(id = "test3") {
            step("init") {
                setState("x", 1)
                setState("y", 2)
                checkpoint("cp1")
            }
        }

        val changes = runtime.stateChanges()
        assertTrue(changes.size >= 3)
    }

    @Test
    fun `test snapshot`() = runTest {
        val runtime = kAgentRuntime(id = "test4") {
            step("init") {
                setState("name", "alice")
                setState("age", 30)
                checkpoint("init_done")
            }
        }

        val snapshot = runtime.snapshot()
        assertEquals("test4", snapshot.id)
        assertEquals(RuntimeState.Completed, snapshot.state)
        assertTrue(snapshot.states.containsKey("name"))
        assertTrue(snapshot.checkpointNames.contains("init_done"))
    }

    @Test
    fun `test replay to checkpoint`() = runTest {
        val runtime = kAgentRuntime(id = "test5") {
            step("init") {
                setState("a", 1)
                checkpoint("cp1")
            }
            step("modify") {
                setState("a", 2)
            }
        }

        assertEquals(2, runtime.getState<Int>("a"))

        runtime.replayToCheckpoint("cp1")
        assertEquals(1, runtime.getState<Int>("a"))
    }

    @Test
    fun `test derived state`() = runTest {
        val runtime = kAgentRuntime(id = "test6") {
            step("compute") {
                setState("a", 10)
                setState("b", 20)

                registerDerived("sum", setOf("a", "b")) {
                    val a = getStateWithTracking("a") as? Int ?: 0
                    val b = getStateWithTracking("b") as? Int ?: 0
                    a + b
                }
            }
        }

        val sum = runtime.getState<Int>("derived:sum")
        assertEquals(30, sum)
    }

    @Test
    fun `test get all states`() = runTest {
        val runtime = kAgentRuntime(id = "test7") {
            step("init") {
                setState("x", 1)
                setState("y", "hello")
                setState("z", true)
            }
        }

        val allStates = runtime.getState()
        assertEquals(3, allStates.size)
        assertEquals(1, allStates["x"])
        assertEquals("hello", allStates["y"])
        assertEquals(true, allStates["z"])
    }

    @Test
    fun `test DSL builder`() = runTest {
        val runtime = kAgentRuntimeBuilder {
            id("builder-test")
            maxConcurrency(2)

            run {
                step("init") {
                    setState("key", "value")
                }
            }
        }

        assertEquals("builder-test", runtime.id)
        assertEquals("value", runtime.getState<String>("key"))
    }

    @Test
    fun `test events flow`() = runTest {
        val runtime = kAgentRuntime(id = "test9") {
            step("init") {
                setState("a", 1)
            }
        }

        // Events should have been emitted
        val changes = runtime.stateChanges()
        assertTrue(changes.isNotEmpty())
    }

    @Test
    fun `test runtime with custom memory`() = runTest {
        val mem = com.cogent.memory.core.Memory()
        mem.setState("preloaded", "data")

        val runtime = kAgentRuntime(id = "test10", memory = mem) {
            step("init") {
                setState("new", "value")
            }
        }

        assertEquals("data", runtime.getState<String>("preloaded"))
        assertEquals("value", runtime.getState<String>("new"))
    }
}
