package com.cogent.fiber.scheduler

import com.cogent.fiber.GlobalObservationContext
import com.cogent.memory.core.Memory
import com.cogent.memory.dependency.DependencyTracker
import com.cogent.memory.dependency.InvalidationGraph
import com.cogent.memory.snapshot.DefaultMemorySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class RuntimeHeartState {
    object Idle : RuntimeHeartState()
    object Running : RuntimeHeartState()
    object Suspended : RuntimeHeartState()
    object Completed : RuntimeHeartState()
    data class Error(val exception: Throwable) : RuntimeHeartState()
}

class RuntimeHeart(
    private val id: String = "default",
    private val memory: Memory,
    private val invalidationGraph: InvalidationGraph = InvalidationGraph(),
    private val dependencyTracker: DependencyTracker = DependencyTracker(),
    maxConcurrency: Int = 4,
    private val externalScope: CoroutineScope? = null
) {
    private val mutex = Mutex()
    private val supervisor = SupervisorJob()
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(Dispatchers.Default + supervisor)

    private var _state: RuntimeHeartState = RuntimeHeartState.Idle
    val state: RuntimeHeartState get() = _state

    private val scheduler = AgentScheduler(
        maxConcurrency = maxConcurrency,
        scope = scope
    )

    private val derivedStates = mutableMapOf<String, suspend () -> Any?>()
    private val derivedDependencies = mutableMapOf<String, Set<String>>()

    private val stateHistory = mutableListOf<StateChangeRecord>()

    init {
        scope.launch {
            scheduler.events.collect { event ->
                when (event) {
                    is SchedulerEvent.Invalidated -> {
                        handleInvalidation(event.key, event.affectedTasks)
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleInvalidation(key: String, affectedTasks: Set<String>) {
        val affectedDerived = dependencyTracker.getDependents(key)

        affectedDerived.forEach { derivedId ->
            val computeFn = derivedStates[derivedId]
            if (computeFn != null) {
                scheduler.scheduleRecompute(derivedId) {
                    try {
                        val newValue = computeFn()
                        recordDerivedRecompute(derivedId, newValue, null)
                    } catch (e: Exception) {
                        recordDerivedRecompute(derivedId, null, e)
                    }
                }
            }
        }
    }

    suspend fun run(block: suspend RuntimeHeartScope.() -> Unit) {
        _state = RuntimeHeartState.Running
        val runtimeScope = RuntimeHeartScope(this)
        try {
            block(runtimeScope)
            // Wait for all scheduled tasks to complete
            scheduler.waitForIdle()
            _state = RuntimeHeartState.Completed
        } catch (e: CancellationException) {
            _state = RuntimeHeartState.Idle
        } catch (e: Exception) {
            _state = RuntimeHeartState.Error(e)
        }
    }

    suspend fun step(id: String, priority: Int = 0, block: suspend RuntimeHeartScope.() -> Unit) {
        val startTime = System.currentTimeMillis()

        scheduler.schedule(
            id = "step:$id",
            type = TaskType.STEP_EXECUTE,
            priority = priority
        ) {
            val scope = RuntimeHeartScope(this@RuntimeHeart)
            scope.currentStepId = id
            try {
                block(scope)
                val duration = System.currentTimeMillis() - startTime
                recordStepComplete(id, duration)
            } catch (e: Exception) {
                recordStepFail(id, e)
                throw e
            }
        }
    }

    suspend fun setState(key: String, value: Any?) {
        val oldValue = memory.getStateBlocking<Any?>(key)
        memory.setState(key, value)

        recordStateChange(key, oldValue, value)

        scheduler.invalidate(key)
    }

    suspend fun <T> getState(key: String): T? {
        return memory.getState(key)
    }

    suspend fun getStateWithTracking(key: String): Any? {
        val value = memory.getStateBlocking<Any?>(key)
        if (GlobalObservationContext.isTracking()) {
            GlobalObservationContext.recordDependency(key)
        }
        return value
    }

    suspend fun registerDerived(
        id: String,
        dependencies: Set<String>,
        compute: suspend () -> Any?
    ) {
        derivedStates[id] = compute
        derivedDependencies[id] = dependencies

        dependencies.forEach { dep ->
            dependencyTracker.addDependency(dep, id)
        }

        val initialValue = compute()
        memory.setState("derived:$id", initialValue)
        recordDerivedRecompute(id, initialValue, null)
    }

    suspend fun derivedSuspend(id: String, compute: suspend () -> Any?): Any? {
        GlobalObservationContext.startTracking()
        val result = try {
            compute()
        } finally {
            GlobalObservationContext.stopTracking()
        }
        val deps = GlobalObservationContext.stopTracking()

        derivedStates[id] = compute
        derivedDependencies[id] = deps

        deps.forEach { dep ->
            dependencyTracker.addDependency(dep, id)
        }

        return result
    }

    suspend fun checkpoint(name: String) {
        val snapshot = memory.snapshot()
        scheduler.schedule(
            id = "checkpoint:$name",
            type = TaskType.CHECKPOINT,
            priority = 200
        ) {
            recordCheckpoint(name, snapshot)
        }
    }

    suspend fun replayToCheckpoint(name: String) {
        val checkpointRecord = stateHistory.lastOrNull {
            it.type == StateChangeType.CHECKPOINT && it.metadata["name"] == name
        }

        if (checkpointRecord != null) {
            val snapshotState = checkpointRecord.metadata["states"] as? Map<String, Any?>
            if (snapshotState != null) {
                val snapshot = DefaultMemorySnapshot(
                    timestamp = checkpointRecord.timestamp,
                    states = snapshotState
                )
                memory.restore(snapshot)
                recordReplay(name)
            }
        }
    }

    fun getScheduler(): AgentScheduler = scheduler

    fun getMemory(): Memory = memory

    fun getInvalidationGraph(): InvalidationGraph = invalidationGraph

    fun getDependencyTracker(): DependencyTracker = dependencyTracker

    suspend fun getStateHistory(): List<StateChangeRecord> {
        return mutex.withLock { stateHistory.toList() }
    }

    suspend fun getStateChanges(): List<StateChangeRecord> {
        return mutex.withLock { stateHistory.filter { it.type == StateChangeType.STATE_CHANGE } }
    }

    suspend fun getCheckpoints(): List<StateChangeRecord> {
        return mutex.withLock { stateHistory.filter { it.type == StateChangeType.CHECKPOINT } }
    }

    suspend fun getDerivedRecomputes(): List<StateChangeRecord> {
        return mutex.withLock { stateHistory.filter { it.type == StateChangeType.DERIVED_RECOMPUTE } }
    }

    private suspend fun recordStateChange(key: String, oldValue: Any?, newValue: Any?) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.STATE_CHANGE,
                timestamp = System.currentTimeMillis(),
                key = key,
                oldValue = oldValue,
                newValue = newValue
            ))
        }
    }

    private suspend fun recordStepComplete(id: String, duration: Long) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.STEP_COMPLETE,
                timestamp = System.currentTimeMillis(),
                key = "step:$id",
                metadata = mapOf("duration" to duration)
            ))
        }
    }

    private suspend fun recordStepFail(id: String, error: Throwable) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.STEP_FAIL,
                timestamp = System.currentTimeMillis(),
                key = "step:$id",
                metadata = mapOf("error" to error.message)
            ))
        }
    }

    private suspend fun recordDerivedRecompute(id: String, value: Any?, error: Throwable?) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.DERIVED_RECOMPUTE,
                timestamp = System.currentTimeMillis(),
                key = "derived:$id",
                newValue = value,
                metadata = if (error != null) mapOf("error" to error.message) else emptyMap()
            ))
        }
    }

    private suspend fun recordCheckpoint(name: String, snapshot: com.cogent.memory.snapshot.MemorySnapshot) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.CHECKPOINT,
                timestamp = System.currentTimeMillis(),
                key = "checkpoint:$name",
                metadata = mapOf(
                    "name" to name,
                    "states" to snapshot.states
                )
            ))
        }
    }

    private suspend fun recordReplay(checkpointName: String) {
        mutex.withLock {
            stateHistory.add(StateChangeRecord(
                type = StateChangeType.REPLAY,
                timestamp = System.currentTimeMillis(),
                key = "replay:$checkpointName",
                metadata = mapOf("checkpointName" to checkpointName)
            ))
        }
    }

    fun cancel() {
        supervisor.cancel()
        _state = RuntimeHeartState.Idle
    }

    override fun toString(): String {
        return "RuntimeHeart(id='$id', state=$_state, scheduler=$scheduler)"
    }
}

class RuntimeHeartScope(
    private val heart: RuntimeHeart
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

    suspend fun step(id: String, priority: Int = 0, block: suspend RuntimeHeartScope.() -> Unit) {
        heart.step(id, priority, block)
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

data class StateChangeRecord(
    val type: StateChangeType,
    val timestamp: Long,
    val key: String,
    val oldValue: Any? = null,
    val newValue: Any? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

enum class StateChangeType {
    STATE_CHANGE,
    STEP_COMPLETE,
    STEP_FAIL,
    DERIVED_RECOMPUTE,
    CHECKPOINT,
    REPLAY,
    INVALIDATION
}

fun runtimeHeart(
    id: String = "default",
    memory: Memory,
    block: suspend RuntimeHeartScope.() -> Unit
): RuntimeHeart {
    val heart = RuntimeHeart(id = id, memory = memory)
    runBlocking {
        heart.run(block)
        delay(500)
    }
    return heart
}
