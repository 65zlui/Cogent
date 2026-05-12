package com.kagent.runtime

import com.kagent.memory.core.Memory
import com.kagent.fiber.scheduler.AgentScheduler
import com.kagent.fiber.scheduler.RuntimeHeart
import com.kagent.fiber.scheduler.RuntimeHeartState
import com.kagent.fiber.scheduler.ScheduledTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Unified Agent Runtime API (v0.4)
 * 
 * The only entry point for users. All internal modules (Scheduler, Fiber, 
 * Dependency Tracking) are hidden behind this facade.
 * 
 * Usage:
 * ```kotlin
 * val runtime = kAgentRuntime(id = "demo") {
 *     step("init") {
 *         setState("task", "hello")
 *         checkpoint("init_done")
 *     }
 * }
 * 
 * runtime.events.collect { event -> println(event) }
 * val snapshot = runtime.snapshot()
 * ```
 */
class KAgentRuntime internal constructor(
    val id: String,
    private val heart: RuntimeHeart,
    internal val interceptorList: MutableList<StepInterceptor> = mutableListOf()
) {

    /**
     * Register a step interceptor.
     * Interceptors wrap each step() call in a chain (OkHttp pattern).
     * They can observe, modify, or short-circuit execution.
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
     * Event flow for observing runtime activity
     */
    val events: Flow<AgentEvent>
        get() = heart.getScheduler().events.map { schedulerEvent ->
            when (schedulerEvent) {
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskScheduled -> AgentEvent.TaskScheduled(schedulerEvent.task.id)
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskStarted -> AgentEvent.TaskStarted(schedulerEvent.task.id)
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskCompleted -> AgentEvent.TaskCompleted(schedulerEvent.task.id)
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskFailed -> AgentEvent.TaskFailed(schedulerEvent.task.id, schedulerEvent.error)
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskSuspended -> AgentEvent.TaskSuspended(schedulerEvent.task.id)
                is com.kagent.fiber.scheduler.SchedulerEvent.TaskResumed -> AgentEvent.TaskResumed(schedulerEvent.task.id)
                is com.kagent.fiber.scheduler.SchedulerEvent.Invalidated -> AgentEvent.Invalidated(schedulerEvent.key, schedulerEvent.affectedTasks)
            }
        }

    /**
     * State changes timeline (derived from execution history)
     */
    fun stateChanges(): List<StateChange> = runBlocking {
        heart.getStateHistory().map { record ->
            StateChange(
                type = when (record.type) {
                    com.kagent.fiber.scheduler.StateChangeType.STATE_CHANGE -> StateChangeType.VALUE_SET
                    com.kagent.fiber.scheduler.StateChangeType.STEP_COMPLETE -> StateChangeType.STEP_COMPLETE
                    com.kagent.fiber.scheduler.StateChangeType.STEP_FAIL -> StateChangeType.STEP_FAIL
                    com.kagent.fiber.scheduler.StateChangeType.DERIVED_RECOMPUTE -> StateChangeType.DERIVED_RECOMPUTE
                    com.kagent.fiber.scheduler.StateChangeType.CHECKPOINT -> StateChangeType.CHECKPOINT
                    com.kagent.fiber.scheduler.StateChangeType.REPLAY -> StateChangeType.REPLAY
                    com.kagent.fiber.scheduler.StateChangeType.INVALIDATION -> StateChangeType.INVALIDATION
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

    /**
     * Cancel the runtime
     */
    fun cancel() {
        heart.cancel()
    }

    /**
     * Create an AgentScope with the current interceptors.
     * Used internally by builder and factory functions.
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

        val runtime = KAgentRuntime(id, heart, interceptors.toMutableList())

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
 * Execution scope provided in run {} blocks
 */
class KAgentRuntimeScope internal constructor(
    private val heart: RuntimeHeart,
    private val interceptors: List<StepInterceptor> = emptyList()
) {
    var currentStepId: String? = null

    suspend fun setState(key: String, value: Any?) {
        heart.setState(key, value)
    }

    suspend fun <T> getState(key: String): T? {
        return heart.getState(key)
    }

    suspend fun getStateWithTracking(key: String): Any? {
        return heart.getStateWithTracking(key)
    }

    suspend fun step(id: String, priority: Int = 0, block: suspend KAgentRuntimeScope.() -> Unit) {
        val outerScope = this

        if (interceptors.isEmpty()) {
            // Fast path: no interceptors, execute directly
            heart.step(id, priority) {
                currentStepId = id
                block(outerScope)
            }
            return
        }

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

    suspend fun registerDerived(id: String, dependencies: Set<String>, compute: suspend () -> Any?) {
        heart.registerDerived(id, dependencies, compute)
    }

    suspend fun derivedSuspend(id: String, compute: suspend () -> Any?): Any? {
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

    val runtime = KAgentRuntime(id, heart)

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
 * Agent event types
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
