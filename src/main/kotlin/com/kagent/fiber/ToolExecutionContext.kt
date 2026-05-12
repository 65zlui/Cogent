package com.kagent.fiber

import com.kagent.memory.core.Memory
import com.kagent.memory.dependency.DependencyTracker
import com.kagent.memory.dependency.InvalidationGraph
import com.kagent.memory.snapshot.MemorySnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ToolDefinition(
    val name: String,
    val description: String = "",
    val execute: suspend (Map<String, Any?>) -> Any?
)

data class ToolCallRecord(
    val toolName: String,
    val args: Map<String, Any?>,
    val result: Any?,
    val error: String?,
    val startTime: Long,
    val endTime: Long,
    val stepId: String
)

class ToolExecutionContext(
    private val memory: Memory,
    private val timeline: ExecutionTimeline
) {
    private val mutex = Mutex()
    private val callHistory = mutableListOf<ToolCallRecord>()
    private val registeredTools = mutableMapOf<String, ToolDefinition>()

    fun registerTool(tool: ToolDefinition) {
        registeredTools[tool.name] = tool
    }

    fun registerTool(name: String, description: String = "", execute: suspend (Map<String, Any?>) -> Any?) {
        registeredTools[name] = ToolDefinition(name, description, execute)
    }

    suspend fun callTool(
        toolName: String,
        args: Map<String, Any?> = emptyMap(),
        stepId: String
    ): Any? {
        val tool = registeredTools[toolName]
            ?: throw IllegalArgumentException("Tool not registered: $toolName")

        timeline.recordToolCall(toolName, stepId)
        val startTime = System.currentTimeMillis()

        return try {
            val result = tool.execute(args)
            val endTime = System.currentTimeMillis()

            val record = ToolCallRecord(
                toolName = toolName,
                args = args,
                result = result,
                error = null,
                startTime = startTime,
                endTime = endTime,
                stepId = stepId
            )

            mutex.withLock {
                callHistory.add(record)
            }

            timeline.recordToolResult(toolName, stepId, result)
            result
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val record = ToolCallRecord(
                toolName = toolName,
                args = args,
                result = null,
                error = e.message,
                startTime = startTime,
                endTime = endTime,
                stepId = stepId
            )

            mutex.withLock {
                callHistory.add(record)
            }

            timeline.recordToolError(toolName, stepId, e.message.orEmpty())
            throw e
        }
    }

    suspend fun getCallHistory(): List<ToolCallRecord> {
        return mutex.withLock { callHistory.toList() }
    }

    suspend fun getToolCallsForStep(stepId: String): List<ToolCallRecord> {
        return mutex.withLock { callHistory.filter { it.stepId == stepId } }
    }

    suspend fun getRegisteredTools(): Map<String, ToolDefinition> {
        return registeredTools.toMap()
    }

    fun getMemory(): Memory = memory
}
