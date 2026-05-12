package com.kagent.fiber

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

enum class FiberState {
    PENDING,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class FiberPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

data class FiberTask(
    val id: String,
    val priority: FiberPriority,
    val block: suspend () -> Unit,
    var state: FiberState = FiberState.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var error: Throwable? = null
) : Comparable<FiberTask> {
    override fun compareTo(other: FiberTask): Int {
        return other.priority.ordinal - this.priority.ordinal
    }
}

class AgentFiber(
    private val id: String = "default",
    maxConcurrency: Int = 4
) {
    private val mutex = Mutex()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)

    private val taskQueue = PriorityBlockingQueue<FiberTask>()
    private val runningTasks = mutableMapOf<String, Job>()
    private val suspendedTasks = mutableMapOf<String, suspend () -> Unit>()
    private val taskStates = mutableMapOf<String, FiberState>()

    private val workerCount = AtomicInteger(0)
    private val maxConcurrency = maxConcurrency

    private val _stateChanges = kotlinx.coroutines.flow.MutableSharedFlow<FiberStateChange>(
        extraBufferCapacity = 64
    )
    val stateChanges: kotlinx.coroutines.flow.Flow<FiberStateChange> = _stateChanges

    init {
        repeat(maxConcurrency) {
            startWorker()
        }
    }

    private fun startWorker() {
        scope.launch {
            while (isActive) {
                val task = withTimeoutOrNull(100) {
                    kotlinx.coroutines.delay(10)
                    taskQueue.poll()
                }

                if (task != null) {
                    executeTask(task)
                }
            }
        }
    }

    private fun executeTask(task: FiberTask) {
        scope.launch {
            mutex.withLock {
                task.state = FiberState.RUNNING
                task.startedAt = System.currentTimeMillis()
                taskStates[task.id] = FiberState.RUNNING
                runningTasks[task.id] = coroutineContext[Job]!!
            }

            _stateChanges.tryEmit(FiberStateChange(task.id, FiberState.RUNNING))

            try {
                task.block()

                mutex.withLock {
                    task.state = FiberState.COMPLETED
                    task.completedAt = System.currentTimeMillis()
                    taskStates[task.id] = FiberState.COMPLETED
                    runningTasks.remove(task.id)
                }

                _stateChanges.tryEmit(FiberStateChange(task.id, FiberState.COMPLETED))
            } catch (e: CancellationException) {
                mutex.withLock {
                    task.state = FiberState.CANCELLED
                    task.completedAt = System.currentTimeMillis()
                    taskStates[task.id] = FiberState.CANCELLED
                    runningTasks.remove(task.id)
                }
                _stateChanges.tryEmit(FiberStateChange(task.id, FiberState.CANCELLED))
            } catch (e: Exception) {
                mutex.withLock {
                    task.state = FiberState.FAILED
                    task.error = e
                    task.completedAt = System.currentTimeMillis()
                    taskStates[task.id] = FiberState.FAILED
                    runningTasks.remove(task.id)
                }
                _stateChanges.tryEmit(FiberStateChange(task.id, FiberState.FAILED, e))
            }
        }
    }

    suspend fun submit(task: FiberTask) {
        mutex.withLock {
            taskStates[task.id] = FiberState.PENDING
        }
        taskQueue.offer(task)
        _stateChanges.tryEmit(FiberStateChange(task.id, FiberState.PENDING))
    }

    suspend fun submit(
        id: String,
        priority: FiberPriority = FiberPriority.NORMAL,
        block: suspend () -> Unit
    ) {
        submit(FiberTask(id, priority, block))
    }

    suspend fun suspendTask(taskId: String) {
        mutex.withLock {
            if (taskStates[taskId] == FiberState.RUNNING) {
                taskStates[taskId] = FiberState.SUSPENDED
                runningTasks[taskId]?.cancel()
            }
        }
        _stateChanges.tryEmit(FiberStateChange(taskId, FiberState.SUSPENDED))
    }

    suspend fun resumeTask(taskId: String, block: suspend () -> Unit) {
        mutex.withLock {
            if (taskStates[taskId] == FiberState.SUSPENDED) {
                taskStates[taskId] = FiberState.PENDING
                val task = FiberTask(taskId, FiberPriority.NORMAL, block)
                taskQueue.offer(task)
            }
        }
        _stateChanges.tryEmit(FiberStateChange(taskId, FiberState.PENDING))
    }

    suspend fun cancelTask(taskId: String) {
        mutex.withLock {
            runningTasks[taskId]?.cancel()
            runningTasks.remove(taskId)
            taskStates[taskId] = FiberState.CANCELLED
        }
        _stateChanges.tryEmit(FiberStateChange(taskId, FiberState.CANCELLED))
    }

    suspend fun getTaskState(taskId: String): FiberState? {
        return mutex.withLock { taskStates[taskId] }
    }

    suspend fun getAllTaskStates(): Map<String, FiberState> {
        return mutex.withLock { taskStates.toMap() }
    }

    suspend fun getCompletedTasks(): List<String> {
        return mutex.withLock {
            taskStates.filter { it.value == FiberState.COMPLETED }.keys.toList()
        }
    }

    suspend fun getFailedTasks(): List<String> {
        return mutex.withLock {
            taskStates.filter { it.value == FiberState.FAILED }.keys.toList()
        }
    }

    suspend fun getPendingTasks(): List<String> {
        return mutex.withLock {
            taskStates.filter { it.value == FiberState.PENDING }.keys.toList()
        }
    }

    fun cancelAll() {
        supervisor.cancel()
    }

    override fun toString(): String {
        return "AgentFiber(id='$id', tasks=${taskStates.size})"
    }
}

data class FiberStateChange(
    val taskId: String,
    val newState: FiberState,
    val error: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis()
)
