@file:Suppress("DEPRECATION")

package com.cogent.fiber

import com.cogent.memory.core.Memory
import com.cogent.memory.dependency.DependencyTracker
import com.cogent.memory.dependency.InvalidationGraph
import com.cogent.memory.snapshot.MemorySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class FiberRuntimeState {
    object Idle : FiberRuntimeState()
    object Running : FiberRuntimeState()
    object Suspended : FiberRuntimeState()
    object Completed : FiberRuntimeState()
    data class Error(val exception: Throwable) : FiberRuntimeState()
}

@Deprecated("Use KAgentRuntime from com.cogent.runtime instead. FiberRuntime is replaced by RuntimeHeart internally.", ReplaceWith("KAgentRuntime", "com.cogent.runtime.KAgentRuntime"))
class FiberRuntime(
    val id: String = "default",
    private val memory: Memory,
    private val invalidationGraph: InvalidationGraph = InvalidationGraph(),
    maxFiberConcurrency: Int = 4
) {
    private val mutex = Mutex()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private var _state: FiberRuntimeState = FiberRuntimeState.Idle
    val state: FiberRuntimeState get() = _state

    val tracker: DependencyTracker = DependencyTracker()

    private val fiber = AgentFiber(id = "$id-fiber", maxConcurrency = maxFiberConcurrency)

    private val timeline = ExecutionTimeline()

    private val toolContext = ToolExecutionContext(memory, timeline)

    private val derivedStates = mutableMapOf<String, SuspendableDerivedState<*>>()

    private var currentStepId: String? = null

    fun getTimelineRef(): ExecutionTimeline = timeline

    fun getToolContext(): ToolExecutionContext = toolContext

    fun getFiber(): AgentFiber = fiber

    fun getMemory(): Memory = memory

    fun getInvalidationGraph(): InvalidationGraph = invalidationGraph

    suspend fun run(block: suspend FiberRuntimeScope.() -> Unit) {
        mutex.withLock {
            _state = FiberRuntimeState.Running
            timeline.record(
                com.cogent.fiber.ExecutionRecord(
                    stepId = "runtime",
                    type = ExecutionType.STEP_START,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        val runtimeScope = FiberRuntimeScope(this)

        scope.launch {
            try {
                block(runtimeScope)
                _state = FiberRuntimeState.Completed
                timeline.record(
                    com.cogent.fiber.ExecutionRecord(
                        stepId = "runtime",
                        type = ExecutionType.STEP_COMPLETE,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: CancellationException) {
                _state = FiberRuntimeState.Idle
            } catch (e: Exception) {
                _state = FiberRuntimeState.Error(e)
                timeline.record(
                    com.cogent.fiber.ExecutionRecord(
                        stepId = "runtime",
                        type = ExecutionType.STEP_FAIL,
                        timestamp = System.currentTimeMillis(),
                        error = e.message
                    )
                )
            }
        }
    }

    suspend fun step(id: String, priority: FiberPriority = FiberPriority.NORMAL, block: suspend FiberRuntimeScope.() -> Unit) {
        val startTime = System.currentTimeMillis()
        currentStepId = id

        timeline.recordStepStart(id)

        try {
            val scope = FiberRuntimeScope(this)
            scope.currentStepId = id
            block(scope)

            val duration = System.currentTimeMillis() - startTime
            timeline.recordStepComplete(id, duration)
        } catch (e: Exception) {
            timeline.recordStepFail(id, e.message.orEmpty())
            throw e
        } finally {
            currentStepId = null
        }
    }

    suspend fun setState(key: String, value: Any?) {
        val oldValue = memory.getStateBlocking<Any?>(key)
        memory.setState(key, value)
        timeline.recordStateChange(key, oldValue, value)
        invalidationGraph.invalidate(key)
    }

    suspend fun <T> getState(key: String): T? {
        return memory.getState(key)
    }

    suspend fun getStateWithTracking(key: String): Any? {
        if (GlobalObservationContext.isTracking()) {
            GlobalObservationContext.recordDependency(key)
        }
        return memory.getStateBlocking<Any?>(key)
    }

    suspend fun <T> derivedSuspend(id: String, compute: suspend () -> T): SuspendableDerivedState<T> {
        val derived = com.cogent.fiber.derivedSuspend(
            id = id,
            tracker = tracker,
            compute = compute
        )
        derivedStates[id] = derived
        derived.computeValue()
        return derived
    }

    suspend fun callTool(toolName: String, args: Map<String, Any?> = emptyMap()): Any? {
        val stepId = currentStepId ?: "unknown"
        return toolContext.callTool(toolName, args, stepId)
    }

    fun registerTool(name: String, description: String = "", execute: suspend (Map<String, Any?>) -> Any?) {
        toolContext.registerTool(name, description, execute)
    }

    suspend fun checkpoint(name: String) {
        val state = memory.snapshot()
        timeline.recordCheckpoint(name, state.states)
    }

    suspend fun replayToCheckpoint(name: String) {
        val records = timeline.getRecordsUpToCheckpoint(name)
        val checkpoints = timeline.getRecordsByType(ExecutionType.CHECKPOINT)
        val targetCheckpoint = checkpoints.find {
            it.metadata["checkpointName"] == name
        }

        if (targetCheckpoint != null) {
            val snapshot = com.cogent.memory.snapshot.DefaultMemorySnapshot(
                timestamp = targetCheckpoint.timestamp,
                states = targetCheckpoint.state
            )
            memory.restore(snapshot)
        }
    }

    suspend fun getFullTimeline(): List<ExecutionRecord> {
        return timeline.getFullTimeline()
    }

    suspend fun getToolCallHistory(): List<ToolCallRecord> {
        return toolContext.getCallHistory()
    }

    suspend fun getStateAtCheckpoint(name: String): MemorySnapshot? {
        val checkpoints = timeline.getRecordsByType(ExecutionType.CHECKPOINT)
        val target = checkpoints.find {
            it.metadata["checkpointName"] == name
        }

        return target?.let {
            com.cogent.memory.snapshot.DefaultMemorySnapshot(
                timestamp = it.timestamp,
                states = it.state
            )
        }
    }

    suspend fun getDerivedState(id: String): SuspendableDerivedState<*>? {
        return derivedStates[id]
    }

    suspend fun recomputeAllDerived() {
        derivedStates.values.forEach { derived ->
            derived.invalidate()
        }
    }

    fun cancel() {
        supervisor.cancel()
        _state = FiberRuntimeState.Idle
    }

    override fun toString(): String {
        return "FiberRuntime(id='$id', state=$_state)"
    }
}

@Deprecated("Use KAgentRuntime.from com.cogent.runtime instead. FiberRuntimeScope is internal to the deprecated FiberRuntime.", ReplaceWith("KAgentRuntime", "com.cogent.runtime.KAgentRuntime"))
class FiberRuntimeScope(
    private val runtime: FiberRuntime
) {
    var currentStepId: String? = null

    suspend fun setState(key: String, value: Any?) {
        runtime.setState(key, value)
    }

    suspend fun <T> getState(key: String): T? {
        return runtime.getState(key)
    }

    suspend fun getStateWithTracking(key: String): Any? {
        return runtime.getStateWithTracking(key)
    }

    suspend fun <T> derivedSuspend(id: String, compute: suspend () -> T): SuspendableDerivedState<T> {
        return runtime.derivedSuspend(id, compute)
    }

    suspend fun callTool(toolName: String, args: Map<String, Any?> = emptyMap()): Any? {
        return runtime.callTool(toolName, args)
    }

    suspend fun step(id: String, priority: FiberPriority = FiberPriority.NORMAL, block: suspend FiberRuntimeScope.() -> Unit) {
        runtime.step(id, priority, block)
    }

    suspend fun checkpoint(name: String) {
        runtime.checkpoint(name)
    }

    suspend fun replayToCheckpoint(name: String) {
        runtime.replayToCheckpoint(name)
    }

    fun getMemory(): Memory = runtime.getMemory()

    fun getTimelineRef(): ExecutionTimeline = runtime.getTimelineRef()

    fun getToolContext(): ToolExecutionContext = runtime.getToolContext()
}

@Deprecated("Use kAgentRuntime from com.cogent.runtime instead.", ReplaceWith("kAgentRuntime(id, memory = memory, block = block)", "com.cogent.runtime.kAgentRuntime"))
fun fiberRuntime(
    id: String = "default",
    memory: Memory,
    block: suspend FiberRuntimeScope.() -> Unit
): FiberRuntime {
    val runtime = FiberRuntime(id = id, memory = memory)
    runBlocking {
        runtime.run(block)
        delay(500)
    }
    return runtime
}
