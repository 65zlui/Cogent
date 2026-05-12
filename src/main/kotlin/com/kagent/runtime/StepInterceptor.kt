package com.kagent.runtime

/**
 * OkHttp-style interceptor for step execution.
 *
 * Interceptors can observe, modify, or short-circuit step execution.
 * Each interceptor calls [chain.proceed] to delegate to the next interceptor
 * or the actual step execution at the end of the chain.
 *
 * Usage:
 * ```kotlin
 * val runtime = kAgentRuntime(id = "demo") {
 *     addInterceptor { chain ->
 *         println("→ ${chain.input().id}")
 *         val result = chain.proceed(chain.input())
 *         println("← ${result.id} (${result.durationMs}ms)")
 *         result
 *     }
 *
 *     run {
 *         step("init") { setState("x", 1) }
 *     }
 * }
 * ```
 */
fun interface StepInterceptor {

    /**
     * Intercept a step execution. Call [chain.proceed] to continue the chain.
     */
    suspend fun intercept(chain: StepChain): StepResult
}

/**
 * Input data for a single step execution.
 */
data class StepInput(
    val id: String,
    val priority: Int = 0,
    val args: Map<String, Any?> = emptyMap()
)

/**
 * Result of a single step execution.
 */
data class StepResult(
    val id: String,
    val durationMs: Long,
    val error: String? = null
)

/**
 * Chain of interceptors. Passes execution through each interceptor
 * until reaching the actual step execution at the end.
 */
class StepChain internal constructor(
    private val interceptors: List<StepInterceptor>,
    private val index: Int,
    private val input: StepInput,
    private val execute: suspend (StepInput) -> StepResult
) {

    /**
     * Current step input for this point in the chain.
     */
    fun input(): StepInput = input

    /**
     * Proceed to the next interceptor or execute the step.
     * Interceptors may modify the input before passing it downstream.
     */
    suspend fun proceed(input: StepInput): StepResult {
        if (index >= interceptors.size) {
            return execute(input)
        }
        val next = StepChain(interceptors, index + 1, input, execute)
        return interceptors[index].intercept(next)
    }
}

/**
 * Builder extension to add an interceptor via lambda.
 */
fun KAgentRuntimeBuilder.interceptor(block: suspend StepChain.() -> StepResult) {
    addInterceptor(StepInterceptor { chain -> chain.block() })
}
