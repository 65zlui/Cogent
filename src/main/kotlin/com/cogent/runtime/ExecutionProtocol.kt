package com.cogent.runtime

// ============================================================
// v0.5 Execution Protocol — AgentRequest / AgentResponse
// ============================================================

/**
 * Standardized execution input for the agent runtime.
 *
 * All external calls enter through this single protocol type.
 * The runtime stores [input] and [context] in Memory before executing,
 * making them available to steps via [KAgentRuntimeScope.getState].
 *
 * @param input The primary input/prompt for this execution.
 * @param context Additional key-value context injected into Memory as "ctx:<key>".
 * @param sessionId Optional session identifier for multi-turn interactions.
 * @param traceId Optional trace identifier. Auto-generated if null.
 */
data class AgentRequest(
    val input: String,
    val context: Map<String, Any?> = emptyMap(),
    val sessionId: String? = null,
    val traceId: String? = null
)

/**
 * Standardized execution result.
 *
 * Contains only the execution output and metadata.
 * State observation is done via [KAgentRuntime.stream] or direct
 * [KAgentRuntime.getState] access — [AgentResponse] deliberately
 * excludes state dumps to keep the fast path lightweight.
 *
 * @param output The execution output string.
 * @param traceId Correlates this response to a [stream] event sequence.
 * @param durationMs Total execution time in milliseconds.
 */
data class AgentResponse(
    val output: String,
    val traceId: String,
    val durationMs: Long
)

// ============================================================
// RuntimeEvent — Single unified observability model
// ============================================================

/**
 * Unified event model replacing v0.4's AgentEvent / StateChange / TraceEvent.
 *
 * All runtime observability flows through this single sealed hierarchy.
 * [stream] emits these events; [trace] retrieves them by traceId.
 */
sealed class RuntimeEvent {

    /** Execution started for the given traceId. */
    data class RunStart(val traceId: String) : RuntimeEvent()

    /** A named step began execution. */
    data class StepStart(val stepId: String) : RuntimeEvent()

    /** A state key changed value. */
    data class MemoryChange(
        val key: String,
        val oldValue: Any?,
        val newValue: Any?
    ) : RuntimeEvent()

    /** A derived/computed state was recomputed. */
    data class DerivedRecompute(val key: String) : RuntimeEvent()

    /** A tool was invoked. */
    data class ToolCall(val tool: String, val input: String) : RuntimeEvent()

    /** A tool returned a result. */
    data class ToolResult(val tool: String, val output: String) : RuntimeEvent()

    /** A named step completed. */
    data class StepEnd(val stepId: String) : RuntimeEvent()

    /** Execution completed for the given traceId. */
    data class RunEnd(val traceId: String) : RuntimeEvent()

    override fun toString(): String = when (this) {
        is RunStart -> "RunStart($traceId)"
        is StepStart -> "StepStart($stepId)"
        is MemoryChange -> "MemoryChange($key)"
        is DerivedRecompute -> "DerivedRecompute($key)"
        is ToolCall -> "ToolCall($tool)"
        is ToolResult -> "ToolResult($tool)"
        is StepEnd -> "StepEnd($stepId)"
        is RunEnd -> "RunEnd($traceId)"
    }
}

// ============================================================
// RuntimeInterceptor — Protocol-level control plane
// ============================================================

/**
 * Protocol-level interceptor for [KAgentRuntime.execute].
 *
 * Operates at the request/response boundary — analogous to OkHttp's
 * [okhttp3.Interceptor]. Interceptors can observe, modify, short-circuit,
 * or wrap execution (retry, timing, auth, cache).
 *
 * This is the **control plane**: it decides how requests are handled.
 * For internal step-level observation use [StepInterceptor].
 *
 * Usage:
 * ```kotlin
 * runtime.addRuntimeInterceptor { request, chain ->
 *     println("→ ${request.input}")
 *     val response = chain.proceed(request)
 *     println("← ${response.output}")
 *     response
 * }
 * ```
 */
fun interface RuntimeInterceptor {
    suspend fun intercept(request: AgentRequest, chain: InterceptorChain): AgentResponse
}

/**
 * Chain of protocol interceptors. Passes execution through each
 * interceptor until reaching the actual runtime execution.
 */
interface InterceptorChain {
    suspend fun proceed(request: AgentRequest): AgentResponse
}

/**
 * Internal chain implementation that walks the interceptor list
 * before delegating to the actual execution function.
 */
internal class RuntimeInterceptorChain(
    private val interceptors: List<RuntimeInterceptor>,
    private val index: Int,
    private val execute: suspend (AgentRequest) -> AgentResponse
) : InterceptorChain {

    override suspend fun proceed(request: AgentRequest): AgentResponse {
        if (index >= interceptors.size) {
            return execute(request)
        }
        return interceptors[index].intercept(
            request,
            RuntimeInterceptorChain(interceptors, index + 1, execute)
        )
    }
}

// ============================================================
// traceId generation
// ============================================================

private val traceIdCounter = java.util.concurrent.atomic.AtomicLong(0)

/**
 * Generate a unique trace identifier.
 * Format: "trace_{timestamp}_{counter}" for human readability.
 */
internal fun generateTraceId(): String {
    return "trace_${System.currentTimeMillis()}_${traceIdCounter.incrementAndGet()}"
}
