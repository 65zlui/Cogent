package com.cogent.fiber.scheduler

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TaskType {
    INVALIDATION,
    DERIVED_RECOMPUTE,
    STEP_EXECUTE,
    TOOL_CALL,
    CHECKPOINT,
    REPLAY
}

enum class TaskState {
    PENDING,
    SCHEDULED,
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ScheduledTask(
    val id: String,
    val type: TaskType,
    val priority: Int = 0,
    val dependencies: Set<String> = emptySet(),
    val execute: suspend () -> Unit,
    var state: TaskState = TaskState.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null,
    var error: Throwable? = null,
    val metadata: Map<String, Any?> = emptyMap()
) : Comparable<ScheduledTask> {
    override fun compareTo(other: ScheduledTask): Int {
        return other.priority.compareTo(this.priority)
    }
}

sealed class SchedulerEvent {
    data class TaskScheduled(val task: ScheduledTask) : SchedulerEvent()
    data class TaskStarted(val task: ScheduledTask) : SchedulerEvent()
    data class TaskCompleted(val task: ScheduledTask) : SchedulerEvent()
    data class TaskFailed(val task: ScheduledTask, val error: Throwable) : SchedulerEvent()
    data class TaskSuspended(val task: ScheduledTask) : SchedulerEvent()
    data class TaskResumed(val task: ScheduledTask) : SchedulerEvent()
    data class Invalidated(val key: String, val affectedTasks: Set<String>) : SchedulerEvent()
}

class AgentScheduler(
    private val maxConcurrency: Int = 4,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()

    private val tasks = mutableMapOf<String, ScheduledTask>()
    private val runningTasks = mutableMapOf<String, Job>()
    private val suspendedTasks = mutableMapOf<String, ScheduledTask>()

    private val eventChannel = Channel<SchedulerEvent>(Channel.UNLIMITED)
    val events = eventChannel.receiveAsFlow()

    private val taskQueue = mutableListOf<ScheduledTask>()

    suspend fun schedule(task: ScheduledTask) {
        mutex.withLock {
            task.state = TaskState.SCHEDULED
            tasks[task.id] = task
            taskQueue.add(task)
        }

        eventChannel.trySend(SchedulerEvent.TaskScheduled(task))
        executeTask(task)
    }

    suspend fun schedule(
        id: String,
        type: TaskType,
        priority: Int = 0,
        dependencies: Set<String> = emptySet(),
        metadata: Map<String, Any?> = emptyMap(),
        execute: suspend () -> Unit
    ) {
        schedule(ScheduledTask(
            id = id,
            type = type,
            priority = priority,
            dependencies = dependencies,
            execute = execute,
            metadata = metadata
        ))
    }

    private fun executeTask(task: ScheduledTask) {
        scope.launch {
            try {
                mutex.withLock {
                    task.state = TaskState.RUNNING
                    task.startedAt = System.currentTimeMillis()
                    tasks[task.id] = task
                    runningTasks[task.id] = this.coroutineContext[Job]!!
                }

                eventChannel.trySend(SchedulerEvent.TaskStarted(task))

                task.execute()

                mutex.withLock {
                    task.state = TaskState.COMPLETED
                    task.completedAt = System.currentTimeMillis()
                    runningTasks.remove(task.id)
                }

                eventChannel.trySend(SchedulerEvent.TaskCompleted(task))
            } catch (e: CancellationException) {
                mutex.withLock {
                    task.state = TaskState.SUSPENDED
                    suspendedTasks[task.id] = task
                    runningTasks.remove(task.id)
                }
                eventChannel.trySend(SchedulerEvent.TaskSuspended(task))
            } catch (e: Exception) {
                mutex.withLock {
                    task.state = TaskState.FAILED
                    task.error = e
                    task.completedAt = System.currentTimeMillis()
                    runningTasks.remove(task.id)
                }
                eventChannel.trySend(SchedulerEvent.TaskFailed(task, e))
            }
        }
    }

    suspend fun invalidate(key: String): Set<String> {
        val affected = mutableSetOf<String>()

        mutex.withLock {
            tasks.filter { (id, task) ->
                task.state in setOf(TaskState.RUNNING, TaskState.SCHEDULED) && key in task.dependencies
            }.forEach { (id, task) ->
                affected.add(id)
            }
        }

        eventChannel.trySend(SchedulerEvent.Invalidated(key, affected))

        return affected
    }

    suspend fun scheduleRecompute(derivedId: String, execute: suspend () -> Unit) {
        schedule(
            id = "recompute:$derivedId",
            type = TaskType.DERIVED_RECOMPUTE,
            priority = 100,
            execute = execute
        )
    }

    suspend fun suspendTask(taskId: String) {
        mutex.withLock {
            runningTasks[taskId]?.cancel()
            runningTasks.remove(taskId)
            tasks[taskId]?.let {
                it.state = TaskState.SUSPENDED
                suspendedTasks[taskId] = it
            }
        }
    }

    suspend fun resumeTask(taskId: String) {
        mutex.withLock {
            suspendedTasks[taskId]?.let { task ->
                task.state = TaskState.SCHEDULED
                tasks[taskId] = task
                suspendedTasks.remove(taskId)
                executeTask(task)
            }
        }
    }

    suspend fun cancelTask(taskId: String) {
        mutex.withLock {
            runningTasks[taskId]?.cancel()
            runningTasks.remove(taskId)
            tasks[taskId]?.state = TaskState.CANCELLED
        }
    }

    suspend fun waitForIdle() {
        // Wait until no tasks are running or scheduled
        while (true) {
            val allTasks = mutex.withLock { tasks.mapValues { it.value.state } }
            val hasRunningOrScheduled = allTasks.values.any { 
                it in setOf(TaskState.RUNNING, TaskState.SCHEDULED) 
            }
            if (!hasRunningOrScheduled) {
                break
            }
            delay(10)
        }
    }

    suspend fun getTask(taskId: String): ScheduledTask? {
        return mutex.withLock { tasks[taskId] }
    }

    suspend fun getTaskState(taskId: String): TaskState? {
        return mutex.withLock { tasks[taskId]?.state }
    }

    suspend fun getAllTasks(): Map<String, TaskState> {
        return mutex.withLock { tasks.mapValues { it.value.state } }
    }

    suspend fun getRunningTasks(): List<ScheduledTask> {
        return mutex.withLock { tasks.values.filter { it.state == TaskState.RUNNING } }
    }

    suspend fun getPendingTasks(): List<ScheduledTask> {
        return mutex.withLock { tasks.values.filter { it.state == TaskState.SCHEDULED } }
    }

    suspend fun getSuspendedTasks(): List<ScheduledTask> {
        return mutex.withLock { suspendedTasks.values.toList() }
    }

    suspend fun shutdown() {
        eventChannel.close()
    }

    override fun toString(): String {
        return "AgentScheduler(tasks=${tasks.size}, running=${runningTasks.size})"
    }
}
