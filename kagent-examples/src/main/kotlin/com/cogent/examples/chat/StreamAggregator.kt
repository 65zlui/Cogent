package com.cogent.examples.chat

/**
 * Windowed stream aggregator that converts per-token LLM callbacks into
 * batched [RuntimeEvent.StreamDelta] events for timeline-friendly observability.
 *
 * Aggregation strategy (whichever threshold is reached first):
 * - **Time window**: flush every [windowMs] milliseconds (default 100ms)
 * - **Count window**: flush every [windowTokens] tokens (default 5)
 *
 * Usage:
 * ```kotlin
 * val agg = StreamAggregator(
 *     onStreamStart = { p, m -> scope.streamStart(p, m) },
 *     onStreamDelta = { a, d -> scope.streamDelta(a, d) },
 *     onStreamEnd = { l, m -> scope.streamEnd(l, m) }
 * )
 * agg.start("api.openai.com / gpt-4o-mini", null)
 * llmClient.chatStream(messages, onToken = { token -> agg.accept(token) })
 * agg.end(totalLength, responseModel)
 * ```
 *
 * @param onStreamStart Called once when streaming begins
 * @param onStreamDelta Called on each flush with (accumulated, deltaSinceLastFlush)
 * @param onStreamEnd Called when streaming ends with (totalLength, model)
 * @param windowMs Time window in milliseconds (default 100)
 * @param windowTokens Token count window (default 5)
 */
class StreamAggregator(
    private val onStreamStart: suspend (String?, String?) -> Unit,
    private val onStreamDelta: suspend (String, String) -> Unit,
    private val onStreamEnd: suspend (Int, String?) -> Unit,
    private val windowMs: Long = 100L,
    private val windowTokens: Int = 5
) {
    private val totalAccumulated = StringBuilder()
    private val windowBuffer = StringBuilder()
    private var lastEmitLength = 0
    private var lastEmitTime = 0L
    private var hasStarted = false

    /**
     * Begin a new streaming session.
     * Emits [onStreamStart] and resets all internal state.
     */
    suspend fun start(provider: String? = null, model: String? = null) {
        totalAccumulated.clear()
        windowBuffer.clear()
        lastEmitLength = 0
        lastEmitTime = currentTime()
        hasStarted = true
        onStreamStart(provider, model)
    }

    /**
     * Accept a single token from the stream.
     * May trigger a windowed flush if time or count thresholds are reached.
     */
    suspend fun accept(token: String) {
        if (!hasStarted) return
        totalAccumulated.append(token)
        windowBuffer.append(token)
        val now = currentTime()
        if (windowBuffer.length >= windowTokens || (now - lastEmitTime) >= windowMs) {
            flush()
        }
    }

    /**
     * End the streaming session.
     * Flushes any remaining buffered content and emits [onStreamEnd].
     */
    suspend fun end(totalLength: Int, model: String? = null) {
        if (!hasStarted) return
        flush()
        onStreamEnd(totalLength, model)
        hasStarted = false
    }

    /**
     * Force-flush the current window buffer.
     * Emits [onStreamDelta] with the current accumulated text and the delta
     * content since the last flush.
     */
    suspend fun flush() {
        if (windowBuffer.isEmpty()) return
        val accumulated = totalAccumulated.toString()
        val delta = accumulated.substring(lastEmitLength)
        onStreamDelta(accumulated, delta)
        windowBuffer.clear()
        lastEmitLength = accumulated.length
        lastEmitTime = currentTime()
    }

    private fun currentTime(): Long = System.currentTimeMillis()
}
