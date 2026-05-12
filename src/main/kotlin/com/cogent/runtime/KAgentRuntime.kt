package com.cogent.runtime

import com.cogent.memory.core.Memory
import com.cogent.fiber.scheduler.AgentScheduler
import com.cogent.fiber.scheduler.RuntimeHeart
import com.cogent.fiber.scheduler.RuntimeHeartState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Unified Agent Runtime API (v0.5)
 *
 * Single entry point for all runtime operations. Provides:
 * - [execute]/[stream] dual output paths (Execution Protocol)
 * - [step]/[setState]/[getState] DSL for agent logic
 * - [snapshot]/[replayToCheckpoint] for state management
 *
 * Usage:
 * ```kotlin
 * val runtime = kAgentRuntime(id = "demo") {
 *     run {
 *         step("process") {
 *             val input = getState<String>("input") ?: ""
 *             setState("output", "hello $input")
 *         }
 *     }
 * }
 *
 * // v0.5 protocol
 * val resp = runtime.execute(AgentRequest(input = "world"))
 * println(resp.output) // "hello world"
 *
 * // v0.4 DSL (still works)
 * runtime.snapshot()
 * ```
 */
class KAgentRuntime internal constructor(
    val id: String,
    private val heart: RuntimeHeart,
    internal val interceptorList: MutableList<StepInterceptor> = mutableListOf(),
    internal val executionBlock: suspend KAgentRuntimeScope.() -> Unit = {}
) {
    private val runtimeInterceptors = mutableListOf<RuntimeInterceptor>()
    internal val eventStore = EventStore()

    // ================================================================
    // v0.4 API — unchanged, fully backward compatible
    // ================================================================

    /**
     * Register a step-level interceptor (kernel observability plane).
     * Wraps each [KAgentRuntimeScope.step] call.
     */
    fun addInterceptor(interceptor: StepInterceptor) {
        interceptorList.add(interceptor)
    }

    /**
     * Current runtime state
     */
    val state: RuntimeState
        get() = when (heart.state) {
            is RuntimeHeartState.Idle -> RuntimeState.Idle
            is RuntimeHeartState.Running -> RuntimeState.Running
            is RuntimeHeartState.Suspended -> RuntimeState.Suspended
            is RuntimeHeartState.Completed -> RuntimeState.Completed
            is RuntimeHeartState.Error -> RuntimeState.Error((heart.state as RuntimeHeartState.Error).exception)
        }

    /**
     * Event flow for observing runtime activity (v0.4 style)
     */
    val events: Flow<AgentEvent>
        get() = heart.getScheduler().events.map { schedulerEvent ->
            when (schedulerEvent) {
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskScheduled -> AgentEvent.TaskScheduled(schedulerEvent.task.id)
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskStarted -> AgentEvent.TaskStarted(schedulerEvent.task.id)
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskCompleted -> AgentEvent.TaskCompleted(schedulerEvent.task.id)
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskFailed -> AgentEvent.TaskFailed(schedulerEvent.task.id, schedulerEvent.error)
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskSuspended -> AgentEvent.TaskSuspended(schedulerEvent.task.id)
                is com.cogent.fiber.scheduler.SchedulerEvent.TaskResumed -> AgentEvent.TaskResumed(schedulerEvent.task.id)
                is com.cogent.fiber.scheduler.SchedulerEvent.Invalidated -> AgentEvent.Invalidated(schedulerEvent.key, schedulerEvent.affectedTasks)
            }
        }

    /**
     * State changes timeline (derived from execution history)
     */
    fun stateChanges(): List<StateChange> = runBlocking {
        heart.getStateHistory().map { record ->
            StateChange(
                type = when (record.type) {
                    com.cogent.fiber.scheduler.StateChangeType.STATE_CHANGE -> StateChangeType.VALUE_SET
                    com.cogent.fiber.scheduler.StateChangeType.STEP_COMPLETE -> StateChangeType.STEP_COMPLETE
                    com.cogent.fiber.scheduler.StateChangeType.STEP_FAIL -> StateChangeType.STEP_FAIL
                    com.cogent.fiber.scheduler.StateChangeType.DERIVED_RECOMPUTE -> StateChangeType.DERIVED_RECOMPUTE
                    com.cogent.fiber.scheduler.StateChangeType.CHECKPOINT -> StateChangeType.CHECKPOINT
                    com.cogent.fiber.scheduler.StateChangeType.REPLAY -> StateChangeType.REPLAY
                    com.cogent.fiber.scheduler.StateChangeType.INVALIDATION -> StateChangeType.INVALIDATION
                },
                key = record.key,
                oldValue = record.oldValue,
                newValue = record.newValue,
                timestamp = record.timestamp,
                metadata = record.metadata
            )
        }
    }

    /**
     * Get current memory state
     */
    fun getState(): Map<String, Any?> = heart.getMemory().getAllStates()

    /**
     * Get a specific state value
     */
    fun <T> getState(key: String): T? = heart.getMemory().getStateBlocking(key)

    /**
     * Take a runtime snapshot
     */
    fun snapshot(): RuntimeSnapshot {
        val snapshot = runBlocking { heart.getMemory().snapshot() }
        return RuntimeSnapshot(
            id = id,
            state = this.state,
            timestamp = System.currentTimeMillis(),
            states = snapshot.states,
            checkpointNames = stateChanges()
                .filter { it.type == StateChangeType.CHECKPOINT }
                .map { it.metadata["name"] as? String ?: it.key.substringAfter(':') }
        )
    }

    /**
     * Replay to a named checkpoint
     */
    fun replayToCheckpoint(name: String) {
        runBlocking {
            heart.replayToCheckpoint(name)
        }
    }

    /**
     * Internal runtime access for DSL builders
     */
    internal fun getHeart(): RuntimeHeart = heart

    // ================================================================
    // v0.5 Execution Protocol
    // ================================================================

    /**
     * Register a protocol-level interceptor (control plane).
     * Wraps the entire [execute] call.
     */
    fun addRuntimeInterceptor(interceptor: RuntimeInterceptor) {
        runtimeInterceptors.add(interceptor)
    }

    /**
     * Execute a request through the runtime.
     *
     * Stores [AgentRequest.input] and [AgentRequest.context] in Memory,
     * runs the configured execution block, and returns the result.
     *
     * When [RuntimeInterceptor]s are registered, execution passes through
     * the interceptor chain (OkHttp pattern) before reaching the kernel.
     *
     * @param request The execution request (input, context, traceId).
     * @return AgentResponse containing the output and execution metadata.
     */
    suspend fun execute(request: AgentRequest): AgentResponse {
        val traceId = request.traceId ?: generateTraceId()
        val requestWithTrace = request.copy(traceId = traceId)

        if (runtimeInterceptors.isNotEmpty()) {
            val chain = RuntimeInterceptorChain(runtimeInterceptors, 0) { req ->
                executeInternal(req, traceId)
            }
            return chain.proceed(requestWithTrace)
        }

        return executeInternal(requestWithTrace, traceId)
    }

    /**
     * Execute a request and observe the full event stream in real-time.
     *
     * The returned Flow emits [RuntimeEvent] values as execution progresses:
     * ```
     * RunStart → StepStart → MemoryChange → ... → StepEnd → RunEnd
     * ```
     *
     * Events are also stored and can be retrieved later via [trace].
     */
    fun stream(request: AgentRequest): Flow<RuntimeEvent> = callbackFlow {
        val traceId = request.traceId ?: generateTraceId()

        // executeWithEvents emits RunStart → ... → RunEnd via onEvent
        executeWithEvents(request.copy(traceId = traceId), traceId) { event ->
            storeEvent(traceId, event)
            trySend(event)
        }

        close()
    }

    /**
     * Retrieve stored events for a given traceId.
     * Returns events in chronological order, newest last.
     *
     * @param traceId The trace identifier to look up.
     * @param maxEvents Maximum number of events to return (default 1000).
     */
    fun trace(traceId: String, maxEvents: Int = 1000): List<RuntimeEvent> {
        return eventStore.getEvents(traceId, maxEvents).map { it.event }
    }

    /**
     * Internal execution (no interceptor chain).
     */
    private suspend fun executeInternal(
        request: AgentRequest,
        traceId: String
    ): AgentResponse {
        return executeWithEvents(request, traceId) { event ->
            storeEvent(traceId, event)
        }
    }

    /**
     * Core execution logic with event callback.
     * All execute/stream paths converge here.
     */
    private suspend fun executeWithEvents(
        request: AgentRequest,
        traceId: String,
        onEvent: (RuntimeEvent) -> Unit
    ): AgentResponse {
        val startTime = System.currentTimeMillis()

        // Emit start (onEvent handles both store + flow emission)
        onEvent(RuntimeEvent.RunStart(traceId))

        // Inject request data into memory
        heart.setState("input", request.input)
        request.context.forEach { (k, v) -> heart.setState("ctx:$k", v) }
        if (request.sessionId != null) heart.setState("sessionId", request.sessionId)

        // Create scope with event-emitting capability
        val scope = KAgentRuntimeScope(heart, interceptorList.toList(), traceId, onEvent)

        // Execute the configured block via RuntimeHeart
        heart.run {
            executionBlock(scope)
        }

        // Read output from memory
        val output = heart.getState<String>("output") ?: ""
        val duration = System.currentTimeMillis() - startTime

        // Emit end
        onEvent(RuntimeEvent.RunEnd(traceId))

        return AgentResponse(output = output, traceId = traceId, durationMs = duration)
    }

    /**
     * Thread-safe bounded event store.
     */
    private fun storeEvent(traceId: String, event: RuntimeEvent) {
        eventStore.record(traceId, event)
    }

    /**
     * Cancel the runtime
     */
    fun cancel() {
        heart.cancel()
    }

    /**
     * Access the runtime debugger for execution analysis.
     */
    fun debugger(): RuntimeDebugger = RuntimeDebugger(eventStore, heart.getMemory())

    /**
     * Create a scope with current interceptors.
     * Used internally by builder and factory functions (v0.4 compat).
     */
    internal fun createScope(): KAgentRuntimeScope {
        return KAgentRuntimeScope(heart, interceptorList.toList())
    }
}

/**
 * DSL builder for KAgentRuntime
 */
class KAgentRuntimeBuilder {
    var id: String = "default"
    var maxConcurrency: Int = 4
    private var mem: Memory? = null
    private var runBlock: suspend KAgentRuntimeScope.() -> Unit = {}
    internal val interceptors = mutableListOf<StepInterceptor>()

    fun id(id: String) {
        this.id = id
    }

    fun maxConcurrency(n: Int) {
        this.maxConcurrency = n
    }

    fun memory(m: Memory) {
        this.mem = m
    }

    fun addInterceptor(interceptor: StepInterceptor) {
        interceptors.add(interceptor)
    }

    fun run(block: suspend KAgentRuntimeScope.() -> Unit) {
        this.runBlock = block
    }

    fun build(): KAgentRuntime {
        val memory = mem ?: Memory()

        val heart = RuntimeHeart(
            id = id,
            memory = memory,
            maxConcurrency = maxConcurrency
        )

        val runtime = KAgentRuntime(id, heart, interceptors.toMutableList(), runBlock)

        if (runBlock != {}) {
            runBlocking {
                val scope = runtime.createScope()
                heart.run {
                    runBlock(scope)
                }
                heart.getScheduler().waitForIdle()
            }
        }

        return runtime
    }
}

/**
 * Execution scope provided in run {} blocks and during execute().
 */
class KAgentRuntimeScope internal constructor(
    private val heart: RuntimeHeart,
    private val interceptors: List<StepInterceptor> = emptyList(),
    internal val currentTraceId: String? = null,
    private val onEvent: (RuntimeEvent) -> Unit = {}
) {
    var currentStepId: String? = null

    suspend fun setState(key: String, value: Any?) {
        val oldValue = heart.getState<Any?>(key)
        heart.setState(key, value)
        if (oldValue != value) {
            onEvent(RuntimeEvent.MemoryChange(key, oldValue, value))
        }
    }

    suspend fun <T> getState(key: String): T? {
        return heart.getState(key)
    }

    suspend fun getStateWithTracking(key: String): Any? {
        return heart.getStateWithTracking(key)
    }

    suspend fun step(id: String, priority: Int = 0, block: suspend KAgentRuntimeScope.() -> Unit) {
        onEvent(RuntimeEvent.StepStart(id))
        val outerScope = this

        try {
            if (interceptors.isEmpty()) {
                // Fast path: no interceptors, execute directly
                heart.step(id, priority) {
                    currentStepId = id
                    block(outerScope)
                }
            } else {
                // Interceptor chain: wrap heart.step() execution
                val chain = StepChain(
                    interceptors = interceptors,
                    index = 0,
                    input = StepInput(id = id, priority = priority),
                    execute = { input ->
                        val startTime = System.currentTimeMillis()
                        try {
                            heart.step(input.id, input.priority) {
                                currentStepId = input.id
                                block(outerScope)
                            }
                            StepResult(input.id, System.currentTimeMillis() - startTime)
                        } catch (e: Exception) {
                            StepResult(input.id, System.currentTimeMillis() - startTime, e.message)
                        }
                    }
                )
                chain.proceed(StepInput(id = id, priority = priority))
            }
        } finally {
            onEvent(RuntimeEvent.StepEnd(id))
        }
    }

    suspend fun registerDerived(id: String, dependencies: Set<String>, compute: suspend () -> Any?) {
        heart.registerDerived(id, dependencies, compute)
    }

    suspend fun derivedSuspend(id: String, compute: suspend () -> Any?): Any? {
        onEvent(RuntimeEvent.DerivedRecompute(id))
        return heart.derivedSuspend(id, compute)
    }

    suspend fun checkpoint(name: String) {
        heart.checkpoint(name)
    }

    suspend fun replayToCheckpoint(name: String) {
        heart.replayToCheckpoint(name)
    }

    fun getMemory(): Memory = heart.getMemory()
    fun getScheduler(): AgentScheduler = heart.getScheduler()
}

/**
 * Factory function to create a KAgentRuntime
 */
fun kAgentRuntime(
    id: String = "default",
    maxConcurrency: Int = 4,
    memory: Memory? = null,
    block: suspend KAgentRuntimeScope.() -> Unit = {}
): KAgentRuntime {
    val mem = memory ?: Memory()
    val heart = RuntimeHeart(
        id = id,
        memory = mem,
        maxConcurrency = maxConcurrency
    )

    val runtime = KAgentRuntime(id, heart, mutableListOf(), block)

    if (block != {}) {
        runBlocking {
            val scope = runtime.createScope()
            heart.run {
                block(scope)
            }
            heart.getScheduler().waitForIdle()
        }
    }

    return runtime
}

/**
 * DSL entry point using builder pattern
 */
fun kAgentRuntimeBuilder(block: KAgentRuntimeBuilder.() -> Unit): KAgentRuntime {
    val builder = KAgentRuntimeBuilder()
    block(builder)
    return builder.build()
}

// ================================================================
// v0.4 Type Definitions (kept for backward compatibility)
// ================================================================

/**
 * Runtime state enumeration
 */
sealed class RuntimeState {
    object Idle : RuntimeState()
    object Running : RuntimeState()
    object Suspended : RuntimeState()
    object Completed : RuntimeState()
    data class Error(val exception: Throwable) : RuntimeState()

    override fun toString(): String = when (this) {
        is Idle -> "Idle"
        is Running -> "Running"
        is Suspended -> "Suspended"
        is Completed -> "Completed"
        is Error -> "Error(${exception.message})"
    }
}

/**
 * Agent event types (v0.4, will be deprecated in favor of [RuntimeEvent])
 */
sealed class AgentEvent {
    data class TaskScheduled(val taskId: String) : AgentEvent()
    data class TaskStarted(val taskId: String) : AgentEvent()
    data class TaskCompleted(val taskId: String) : AgentEvent()
    data class TaskFailed(val taskId: String, val error: Throwable) : AgentEvent()
    data class TaskSuspended(val taskId: String) : AgentEvent()
    data class TaskResumed(val taskId: String) : AgentEvent()
    data class Invalidated(val key: String, val affectedTasks: Set<String>) : AgentEvent()

    override fun toString(): String = when (this) {
        is TaskScheduled -> "TaskScheduled($taskId)"
        is TaskStarted -> "TaskStarted($taskId)"
        is TaskCompleted -> "TaskCompleted($taskId)"
        is TaskFailed -> "TaskFailed($taskId, ${error.message})"
        is TaskSuspended -> "TaskSuspended($taskId)"
        is TaskResumed -> "TaskResumed($taskId)"
        is Invalidated -> "Invalidated($key, ${affectedTasks.size} tasks)"
    }
}

/**
 * State change record
 */
data class StateChange(
    val type: StateChangeType,
    val key: String,
    val oldValue: Any? = null,
    val newValue: Any? = null,
    val timestamp: Long,
    val metadata: Map<String, Any?> = emptyMap()
)

enum class StateChangeType {
    VALUE_SET,
    STEP_COMPLETE,
    STEP_FAIL,
    DERIVED_RECOMPUTE,
    CHECKPOINT,
    REPLAY,
    INVALIDATION
}

/**
 * Runtime snapshot
 */
data class RuntimeSnapshot(
    val id: String,
    val state: RuntimeState,
    val timestamp: Long,
    val states: Map<String, Any?>,
    val checkpointNames: List<String>
)
