package com.kagent.fiber

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExecutionRecord(
    val stepId: String,
    val type: ExecutionType,
    val timestamp: Long,
    val duration: Long? = null,
    val state: Map<String, Any?> = emptyMap(),
    val error: String? = null,
    val dependencies: Set<String> = emptySet(),
    val metadata: Map<String, Any?> = emptyMap()
)

enum class ExecutionType {
    STEP_START,
    STEP_COMPLETE,
    STEP_FAIL,
    STEP_SUSPEND,
    STEP_RESUME,
    TOOL_CALL,
    TOOL_RESULT,
    TOOL_ERROR,
    DERIVED_COMPUTE,
    DERIVED_INVALIDATE,
    CHECKPOINT,
    STATE_CHANGE
}

class ExecutionTimeline {
    private val mutex = Mutex()
    private val records = mutableListOf<ExecutionRecord>()
    private val checkpoints = mutableMapOf<String, Int>()

    suspend fun record(record: ExecutionRecord) {
        mutex.withLock {
            records.add(record)
        }
    }

    suspend fun recordStepStart(stepId: String, dependencies: Set<String> = emptySet()) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.STEP_START,
            timestamp = System.currentTimeMillis(),
            dependencies = dependencies
        ))
    }

    suspend fun recordStepComplete(stepId: String, duration: Long, state: Map<String, Any?> = emptyMap()) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.STEP_COMPLETE,
            timestamp = System.currentTimeMillis(),
            duration = duration,
            state = state
        ))
    }

    suspend fun recordStepFail(stepId: String, error: String) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.STEP_FAIL,
            timestamp = System.currentTimeMillis(),
            error = error
        ))
    }

    suspend fun recordStepSuspend(stepId: String) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.STEP_SUSPEND,
            timestamp = System.currentTimeMillis()
        ))
    }

    suspend fun recordStepResume(stepId: String) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.STEP_RESUME,
            timestamp = System.currentTimeMillis()
        ))
    }

    suspend fun recordToolCall(toolName: String, stepId: String) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.TOOL_CALL,
            timestamp = System.currentTimeMillis(),
            metadata = mapOf("toolName" to toolName)
        ))
    }

    suspend fun recordToolResult(toolName: String, stepId: String, result: Any?) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.TOOL_RESULT,
            timestamp = System.currentTimeMillis(),
            state = mapOf("result" to result),
            metadata = mapOf("toolName" to toolName)
        ))
    }

    suspend fun recordToolError(toolName: String, stepId: String, error: String) {
        record(ExecutionRecord(
            stepId = stepId,
            type = ExecutionType.TOOL_ERROR,
            timestamp = System.currentTimeMillis(),
            error = error,
            metadata = mapOf("toolName" to toolName)
        ))
    }

    suspend fun recordDerivedCompute(derivedId: String, duration: Long) {
        record(ExecutionRecord(
            stepId = derivedId,
            type = ExecutionType.DERIVED_COMPUTE,
            timestamp = System.currentTimeMillis(),
            duration = duration
        ))
    }

    suspend fun recordDerivedInvalidate(derivedId: String) {
        record(ExecutionRecord(
            stepId = derivedId,
            type = ExecutionType.DERIVED_INVALIDATE,
            timestamp = System.currentTimeMillis()
        ))
    }

    suspend fun recordCheckpoint(name: String, state: Map<String, Any?> = emptyMap()) {
        val index = mutex.withLock { records.size }
        checkpoints[name] = index
        record(ExecutionRecord(
            stepId = "checkpoint:$name",
            type = ExecutionType.CHECKPOINT,
            timestamp = System.currentTimeMillis(),
            state = state,
            metadata = mapOf("checkpointName" to name)
        ))
    }

    suspend fun recordStateChange(key: String, oldValue: Any?, newValue: Any?) {
        record(ExecutionRecord(
            stepId = "state:$key",
            type = ExecutionType.STATE_CHANGE,
            timestamp = System.currentTimeMillis(),
            state = mapOf("key" to key, "oldValue" to oldValue, "newValue" to newValue)
        ))
    }

    suspend fun getRecords(): List<ExecutionRecord> {
        return mutex.withLock { records.toList() }
    }

    suspend fun getRecordsByType(type: ExecutionType): List<ExecutionRecord> {
        return mutex.withLock { records.filter { it.type == type } }
    }

    suspend fun getCheckpointIndex(name: String): Int? {
        return mutex.withLock { checkpoints[name] }
    }

    suspend fun getRecordsUpToCheckpoint(name: String): List<ExecutionRecord> {
        return mutex.withLock {
            val index = checkpoints[name] ?: return emptyList()
            records.take(index + 1)
        }
    }

    suspend fun getStepTimeline(stepId: String): List<ExecutionRecord> {
        return mutex.withLock {
            records.filter { it.stepId == stepId }
        }
    }

    suspend fun getFullTimeline(): List<ExecutionRecord> {
        return mutex.withLock { records.sortedBy { it.timestamp } }
    }

    suspend fun clear() {
        mutex.withLock {
            records.clear()
            checkpoints.clear()
        }
    }

    suspend fun size(): Int {
        return mutex.withLock { records.size }
    }

    override fun toString(): String {
        return "ExecutionTimeline(records=${records.size}, checkpoints=${checkpoints.size})"
    }
}
