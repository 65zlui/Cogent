@file:Suppress("DEPRECATION")

package com.cogent.runtime

import com.cogent.memory.core.Memory
import com.cogent.memory.dependency.InvalidationGraph
import com.cogent.memory.dependency.RecompositionScope
import com.cogent.trace.ExecutionTrace
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class AgentState {
    object Idle : AgentState()
    object Running : AgentState()
    object Suspended : AgentState()
    object Completed : AgentState()
    data class Error(val exception: Throwable) : AgentState()
}

@Deprecated("Use KAgentRuntime instead. This legacy AgentRuntime is replaced by the unified facade.", ReplaceWith("KAgentRuntime", "com.cogent.runtime.KAgentRuntime"))
class AgentRuntime(
    val id: String = "default",
    private val memory: Memory,
    private val invalidationGraph: InvalidationGraph = InvalidationGraph(),
    private val trace: ExecutionTrace = ExecutionTrace()
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private val mutex = Mutex()
    
    private var _state: AgentState = AgentState.Idle
    val state: AgentState get() = _state
    
    private val stepScheduler = StepScheduler()
    
    val recompositionScope = RecompositionScope(
        scope = scope,
        invalidationGraph = invalidationGraph,
        recomposeHandler = { result ->
            trace.recordInvalidation(result.sourceId, result.affectedNodes)
        }
    )
    
    suspend fun run(agent: suspend AgentRuntime.() -> Unit): Job {
        return mutex.withLock {
            _state = AgentState.Running
            trace.recordEvent("runtime.start", mapOf("agentId" to id))
            
            scope.launch {
                try {
                    agent(this@AgentRuntime)
                    _state = AgentState.Completed
                    trace.recordEvent("runtime.complete", mapOf("agentId" to id))
                } catch (e: CancellationException) {
                    _state = AgentState.Idle
                    trace.recordEvent("runtime.cancel", mapOf("agentId" to id))
                } catch (e: Exception) {
                    _state = AgentState.Error(e)
                    trace.recordEvent("runtime.error", mapOf("agentId" to id, "error" to e.message.orEmpty()))
                }
            }
        }
    }
    
    suspend fun step(name: String, block: suspend () -> Unit) {
        trace.recordEvent("step.start", mapOf("name" to name))
        val startTime = System.currentTimeMillis()
        
        try {
            block()
            val duration = System.currentTimeMillis() - startTime
            trace.recordEvent("step.complete", mapOf("name" to name, "duration" to duration))
        } catch (e: Exception) {
            trace.recordEvent("step.error", mapOf("name" to name, "error" to e.message.orEmpty()))
            throw e
        }
    }
    
    suspend fun <T> tool(name: String, block: suspend () -> T): T {
        trace.recordEvent("tool.start", mapOf("name" to name))
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            trace.recordEvent("tool.complete", mapOf("name" to name, "duration" to duration))
            result
        } catch (e: Exception) {
            trace.recordEvent("tool.error", mapOf("name" to name, "error" to e.message.orEmpty()))
            throw e
        }
    }
    
    suspend fun setState(key: String, value: Any?) {
        memory.setState(key, value)
        recompositionScope.invalidate(key)
    }
    
    suspend fun <T> getState(key: String): T? {
        return memory.getState(key)
    }
    
    suspend fun checkpoint(name: String = "checkpoint_${System.currentTimeMillis()}") {
        val snapshot = memory.snapshot()
        trace.recordCheckpoint(name, snapshot)
    }
    
    fun suspendExecution() {
        _state = AgentState.Suspended
    }
    
    fun resumeExecution() {
        if (_state == AgentState.Suspended) {
            _state = AgentState.Running
        }
    }
    
    fun cancel() {
        supervisor.cancel()
        _state = AgentState.Idle
    }
    
    fun getTrace(): ExecutionTrace = trace
    
    fun getMemory(): Memory = memory
    
    fun getInvalidationGraph(): InvalidationGraph = invalidationGraph
    
    override fun toString(): String {
        return "AgentRuntime(id='$id', state=$_state)"
    }
}

@Deprecated("Use kAgentRuntime from this package instead.", ReplaceWith("kAgentRuntime(id, memory = memory, block = block)"))
fun agentRuntime(
    id: String = "default",
    memory: Memory,
    block: suspend AgentRuntime.() -> Unit
): AgentRuntime {
    val runtime = AgentRuntime(id = id, memory = memory)
    runBlocking {
        runtime.run(block)
    }
    return runtime
}
