package com.cogent.runtime

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ExecutionProtocolTest {

    // ================================================================
    // execute()
    // ================================================================

    @Test
    fun `execute returns output from memory`() = runTest {
        val runtime = kAgentRuntime(id = "ep-1") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "echo: $input")
                }
            }
        }

        val response = runtime.execute(AgentRequest(input = "hello"))

        assertEquals("echo: hello", response.output)
        assertNotNull(response.traceId)
        assertTrue(response.durationMs >= 0)
    }

    @Test
    fun `execute with context stores ctx prefixed keys`() = runTest {
        val runtime = kAgentRuntime(id = "ep-2") {
            run {
                step("process") {
                    val user = getState<String>("ctx:user") ?: "unknown"
                    setState("output", "user=$user")
                }
            }
        }

        val resp = runtime.execute(AgentRequest(
            input = "go",
            context = mapOf("user" to "alice")
        ))

        assertEquals("user=alice", resp.output)
    }

    @Test
    fun `execute with sessionId stores sessionId in memory`() = runTest {
        val runtime = kAgentRuntime(id = "ep-3") {
            run {
                step("check") {
                    val sid = getState<String>("sessionId") ?: "none"
                    setState("output", "session=$sid")
                }
            }
        }

        val resp = runtime.execute(AgentRequest(
            input = "ping",
            sessionId = "sess-001"
        ))

        assertEquals("session=sess-001", resp.output)
    }

    @Test
    fun `execute with explicit traceId uses it`() = runTest {
        val runtime = kAgentRuntime(id = "ep-4") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                }
            }
        }

        val resp = runtime.execute(AgentRequest(
            input = "test",
            traceId = "my-trace-1"
        ))

        assertEquals("my-trace-1", resp.traceId)
    }

    @Test
    fun `execute generates traceId when not provided`() = runTest {
        val runtime = kAgentRuntime(id = "ep-5") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                }
            }
        }

        val resp = runtime.execute(AgentRequest(input = "hello"))

        assertNotNull(resp.traceId)
        assertTrue(resp.traceId.startsWith("trace_"))
    }

    // ================================================================
    // stream()
    // ================================================================

    @Test
    fun `stream emits full event sequence`() = runTest {
        val runtime = kAgentRuntime(id = "ep-6") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                }
            }
        }

        val events = runtime.stream(AgentRequest(input = "hello")).toList()

        assertTrue(events.any { it is RuntimeEvent.RunStart }, "missing RunStart")
        assertTrue(events.any { it is RuntimeEvent.StepStart }, "missing StepStart")
        assertTrue(events.any { it is RuntimeEvent.MemoryChange }, "missing MemoryChange")
        assertTrue(events.any { it is RuntimeEvent.StepEnd }, "missing StepEnd")
        assertTrue(events.any { it is RuntimeEvent.RunEnd }, "missing RunEnd")

        // Verify ordering: RunStart first, RunEnd last
        assertTrue(events.first() is RuntimeEvent.RunStart, "first event should be RunStart")
        assertTrue(events.last() is RuntimeEvent.RunEnd, "last event should be RunEnd")

        // Verify traceId consistency
        val traceId = (events.first() as RuntimeEvent.RunStart).traceId
        val endTraceId = (events.last() as RuntimeEvent.RunEnd).traceId
        assertEquals(traceId, endTraceId, "traceId must be consistent across events")
    }

    @Test
    fun `stream events share same traceId`() = runTest {
        val runtime = kAgentRuntime(id = "ep-7") {
            run {
                step("process") {
                    setState("output", getState("input"))
                }
            }
        }

        val events = runtime.stream(AgentRequest(input = "x")).toList()

        val traceIds = events.filterIsInstance<RuntimeEvent>()
            .map { when (it) {
                is RuntimeEvent.RunStart -> it.traceId
                is RuntimeEvent.RunEnd -> it.traceId
                else -> null
            } }
            .filterNotNull()
            .distinct()

        assertEquals(1, traceIds.size, "all RunStart/RunEnd must share one traceId")
    }

    // ================================================================
    // trace()
    // ================================================================

    @Test
    fun `trace retrieves stored events by traceId`() = runTest {
        val runtime = kAgentRuntime(id = "ep-8") {
            run {
                step("process") {
                    setState("output", getState("input"))
                }
            }
        }

        val resp = runtime.execute(AgentRequest(input = "stored", traceId = "trace-test-1"))
        val stored = runtime.trace("trace-test-1")

        assertTrue(stored.isNotEmpty(), "should have stored events")
        assertTrue(stored.any { it is RuntimeEvent.RunStart }, "should have RunStart")
        assertTrue(stored.any { it is RuntimeEvent.RunEnd }, "should have RunEnd")
    }

    @Test
    fun `trace returns empty for unknown traceId`() = runTest {
        val runtime = kAgentRuntime(id = "ep-9") {
            run {
                step("process") {
                    setState("output", getState("input"))
                }
            }
        }

        val stored = runtime.trace("nonexistent-trace")
        assertTrue(stored.isEmpty())
    }

    // ================================================================
    // RuntimeInterceptor
    // ================================================================

    @Test
    fun `runtime interceptor can modify request`() = runTest {
        val runtime = kAgentRuntime(id = "ep-10") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "processed: $input")
                }
            }
        }

        runtime.addRuntimeInterceptor { request, chain ->
            chain.proceed(request.copy(input = "modified-${request.input}"))
        }

        val resp = runtime.execute(AgentRequest(input = "hello"))
        assertEquals("processed: modified-hello", resp.output)
    }

    @Test
    fun `runtime interceptor can short-circuit`() = runTest {
        val runtime = kAgentRuntime(id = "ep-11") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "real: $input")
                }
            }
        }

        // Record initial state after build
        val initialOutput = runtime.getState<String>("output")

        runtime.addRuntimeInterceptor { request, _ ->
            AgentResponse(
                output = "cached: ${request.input}",
                traceId = request.traceId ?: "unknown",
                durationMs = 0
            )
        }

        val resp = runtime.execute(AgentRequest(input = "new-input"))
        assertEquals("cached: new-input", resp.output)

        // Real step should NOT have executed (short-circuit)
        assertEquals(initialOutput, runtime.getState<String>("output"),
            "memory should be unchanged after short-circuit")
    }

    @Test
    fun `multiple runtime interceptors form a chain`() = runTest {
        val runtime = kAgentRuntime(id = "ep-12") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "result: $input")
                }
            }
        }

        runtime.addRuntimeInterceptor { request, chain ->
            chain.proceed(request.copy(input = "a-${request.input}"))
        }
        runtime.addRuntimeInterceptor { request, chain ->
            chain.proceed(request.copy(input = "b-${request.input}"))
        }

        val resp = runtime.execute(AgentRequest(input = "x"))
        // First interceptor: a-x, second interceptor: b-a-x
        assertEquals("result: b-a-x", resp.output)
    }

    // ================================================================
    // State access after execute
    // ================================================================

    @Test
    fun `memory state is accessible after execute`() = runTest {
        val runtime = kAgentRuntime(id = "ep-13") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                    setState("status", "ok")
                }
            }
        }

        runtime.execute(AgentRequest(input = "work"))

        assertEquals("done: work", runtime.getState<String>("output"))
        assertEquals("ok", runtime.getState<String>("status"))
    }

    @Test
    fun `multiple execute calls are independent`() = runTest {
        val runtime = kAgentRuntime(id = "ep-14") {
            run {
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "result: $input")
                }
            }
        }

        val r1 = runtime.execute(AgentRequest(input = "first"))
        val r2 = runtime.execute(AgentRequest(input = "second"))

        assertEquals("result: first", r1.output)
        assertEquals("result: second", r2.output)
    }

    // ================================================================
    // Integration: execute after snapshot/replay
    // ================================================================

    @Test
    fun `execute works after checkpoint replay`() = runTest {
        val runtime = kAgentRuntime(id = "ep-15") {
            run {
                step("init") {
                    setState("counter", 0)
                    checkpoint("start")
                }
                step("process") {
                    val input = getState<String>("input") ?: ""
                    setState("output", "done: $input")
                }
            }
        }

        // Replay to checkpoint, then execute again
        runtime.replayToCheckpoint("start")

        val resp = runtime.execute(AgentRequest(input = "after-replay"))
        assertEquals("done: after-replay", resp.output)
    }

    // ================================================================
    // v0.4 backward compat (unchanged)
    // ================================================================

    @Test
    fun `v0_4 step API still works`() = runTest {
        val rt = kAgentRuntime(id = "ep-16") {
            step("init") {
                setState("msg", "v0.4 works")
            }
        }

        assertEquals("v0.4 works", rt.getState<String>("msg"))
        assertEquals(RuntimeState.Completed, rt.state)
    }
}
