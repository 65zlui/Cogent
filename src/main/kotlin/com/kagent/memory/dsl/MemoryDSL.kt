package com.kagent.memory.dsl

import com.kagent.memory.core.Memory
import com.kagent.memory.core.MemoryLifecycle
import com.kagent.memory.dependency.DerivedState
import com.kagent.memory.dependency.InvalidationGraph
import com.kagent.memory.dependency.deriveBlocking
import com.kagent.memory.eviction.LRUEvictionPolicy
import com.kagent.memory.snapshot.MemorySnapshot
import com.kagent.runtime.AgentRuntime
import com.kagent.runtime.AgentState
import com.kagent.runtime.agentRuntime
import com.kagent.runtime.KAgentRuntime
import com.kagent.runtime.kAgentRuntime
import com.kagent.trace.ExecutionTrace
import com.kagent.trace.ReplayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MemoryBuilder {
    var id: String = "default"
    var maxCapacity: Int = 100
    private val initialStates = mutableMapOf<String, Any?>()
    private var lifecycle: MemoryLifecycle = MemoryLifecycle.SESSION

    fun id(id: String) {
        this.id = id
    }

    fun maxCapacity(capacity: Int) {
        this.maxCapacity = capacity
    }

    fun <T> state(id: String, value: T, lifecycle: MemoryLifecycle = MemoryLifecycle.SESSION) {
        initialStates[id] = value
    }

    fun ephemeral() {
        lifecycle = MemoryLifecycle.EPHEMERAL
    }

    fun session() {
        lifecycle = MemoryLifecycle.SESSION
    }

    fun persistent() {
        lifecycle = MemoryLifecycle.PERSISTENT
    }

    fun global() {
        lifecycle = MemoryLifecycle.GLOBAL
    }

    fun evictionPolicy(maxCapacity: Int) {
        this.maxCapacity = maxCapacity
    }

    internal fun build(): Memory {
        val memory = Memory(
            id = id,
            maxCapacity = maxCapacity,
            evictionPolicy = LRUEvictionPolicy(maxCapacity)
        )

        runBlocking {
            initialStates.forEach { (key, value) ->
                memory.setState(key, value)
            }
        }

        return memory
    }
}

fun memory(block: MemoryBuilder.() -> Unit): Memory {
    val builder = MemoryBuilder()
    block(builder)
    return builder.build()
}

fun Memory.observe(id: String, callback: (Any?) -> Unit) {
    CoroutineScope(Dispatchers.Default).launch {
        val flow = getStateFlow<Any?>(id)
        flow?.collect { value ->
            callback(value)
        }
    }
}

suspend fun Memory.observeFlow(id: String, callback: suspend (Any?) -> Unit) {
    val flow = getStateFlow<Any?>(id)
    flow?.collect { value ->
        callback(value)
    }
}

fun Memory.flow(id: String): kotlinx.coroutines.flow.StateFlow<Any?>? {
    return runBlocking {
        getStateFlow<Any?>(id)
    }
}

class RuntimeBuilder {
    var id: String = "default"
    private lateinit var runtimeMemory: Memory
    private lateinit var block: suspend AgentRuntime.() -> Unit

    fun id(id: String) {
        this.id = id
    }

    fun memory(memory: Memory) {
        this.runtimeMemory = memory
    }

    fun run(block: suspend AgentRuntime.() -> Unit) {
        this.block = block
    }

    internal fun build(): AgentRuntime {
        require(::runtimeMemory.isInitialized) { "Memory must be set" }
        require(::block.isInitialized) { "Run block must be set" }
        return agentRuntime(
            id = id,
            memory = runtimeMemory,
            block = block
        )
    }
}

fun runtime(block: RuntimeBuilder.() -> Unit): AgentRuntime {
    val builder = RuntimeBuilder()
    block(builder)
    return builder.build()
}

class DerivedStateBuilder<T> {
    private lateinit var derivedTracker: InvalidationGraph
    private lateinit var dependencyIds: Set<String>
    private lateinit var computeFn: () -> T

    fun tracker(tracker: InvalidationGraph) {
        this.derivedTracker = tracker
    }

    fun dependencies(vararg ids: String) {
        this.dependencyIds = ids.toSet()
    }

    fun dependencies(ids: Set<String>) {
        this.dependencyIds = ids
    }

    fun compute(block: () -> T) {
        this.computeFn = block
    }

    internal suspend fun build(): DerivedState<T> {
        require(::derivedTracker.isInitialized) { "Tracker must be set" }
        require(::dependencyIds.isInitialized) { "Dependencies must be set" }
        require(::computeFn.isInitialized) { "Compute block must be set" }
        return deriveBlocking(
            tracker = derivedTracker,
            dependencyIds = dependencyIds,
            compute = computeFn
        )
    }
}

suspend fun <T> derived(block: DerivedStateBuilder<T>.() -> Unit): DerivedState<T> {
    val builder = DerivedStateBuilder<T>()
    block(builder)
    return builder.build()
}

fun ExecutionTrace.getTimelineSync(): List<com.kagent.trace.TimelineEntry> {
    return runBlocking { getTimeline() }
}

fun ExecutionTrace.getEventsSync(): List<com.kagent.trace.TraceEvent> {
    return runBlocking { getEvents() }
}

fun ExecutionTrace.getCheckpointsSync(): List<com.kagent.trace.TraceCheckpoint> {
    return runBlocking { getCheckpoints() }
}

fun AgentRuntime.getStateSync(): AgentState = state

fun AgentRuntime.getTraceSync(): ExecutionTrace = getTrace()

fun AgentRuntime.replayEngine(): ReplayEngine = ReplayEngine(getTrace(), getMemory())

suspend fun AgentRuntime.replayToCheckpoint(name: String) {
    val replay = ReplayEngine(getTrace(), getMemory())
    replay.replayToCheckpoint(name)
}

suspend fun AgentRuntime.getTimelineSync(): List<com.kagent.trace.TimelineEntry> {
    return getTrace().getTimeline()
}

// ===== KAgentRuntime DSL (v0.4) =====

fun KAgentRuntime.stateChangesSync(): List<com.kagent.runtime.StateChange> = stateChanges()

fun KAgentRuntime.snapshotSync(): com.kagent.runtime.RuntimeSnapshot = snapshot()
