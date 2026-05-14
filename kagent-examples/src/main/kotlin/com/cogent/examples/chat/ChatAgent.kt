package com.cogent.examples.chat

import com.cogent.runtime.*

class ChatAgent(
    val id: String = "chat-agent",
    private val tools: Map<String, ChatTool> = emptyMap(),
    val llmClient: LlmClient? = null
) {
    val runtime: KAgentRuntime = kAgentRuntime(id = id) {
        run {
            step("understand") {
                val input = getState<String>("input") ?: ""
                val intent = when {
                    input.contains("?") && input.length > 10 -> "question"
                    input.contains("search", ignoreCase = true) ||
                        input.contains("find", ignoreCase = true) -> "search"
                    input.contains("calc", ignoreCase = true) ||
                        input.contains("+") ||
                        input.contains("*") -> "calculate"
                    input.contains("hello", ignoreCase = true) ||
                        input.contains("hi", ignoreCase = true) -> "greeting"
                    else -> "chat"
                }
                val sentiment = when {
                    input.contains("thank", ignoreCase = true) -> "positive"
                    input.contains("bad", ignoreCase = true) ||
                        input.contains("sad", ignoreCase = true) -> "negative"
                    else -> "neutral"
                }
                setState("intent", intent)
                setState("sentiment", sentiment)
                setState("entities", extractEntities(input))
            }

            step("act") {
                val intent = getState<String>("intent") ?: "chat"
                var toolOutput: String? = null

                when (intent) {
                    "search" -> {
                        val query = getState<String>("input") ?: ""
                        val tool = tools["search"]
                        if (tool != null) {
                            toolCall(tool.name, query)
                            val result = tool.execute(query)
                            toolResult(tool.name, result)
                            toolOutput = result
                        }
                    }
                    "calculate" -> {
                        val expression = getState<String>("input") ?: ""
                        val tool = tools["calculator"]
                        if (tool != null) {
                            toolCall(tool.name, expression)
                            val result = tool.execute(expression)
                            toolResult(tool.name, result)
                            toolOutput = result
                        }
                    }
                }

                // Post-process: summarize tool output if summarize tool available
                val finalResult = if (toolOutput != null && tools.containsKey("summarize")) {
                    toolCall("summarize", toolOutput)
                    val summary = tools["summarize"]!!.execute(toolOutput)
                    toolResult("summarize", summary)
                    setState("tool_summary", summary)
                    summary
                } else {
                    toolOutput
                }

                if (finalResult != null) {
                    setState("tool_result", finalResult)
                }
            }

            step("compose") {
                val input = getState<String>("input") ?: ""
                val intent = getState<String>("intent") ?: "chat"
                val sentiment = getState<String>("sentiment") ?: "neutral"
                val toolResult = getState<String>("tool_result")

                val response = if (llmClient != null && input.isNotBlank()) {
                    setState("llm_provider", llmClient.describe())
                    val systemPrompt = buildLlmSystemPrompt(intent, toolResult)
                    val messages = listOf(
                        LlmMessage("system", systemPrompt),
                        LlmMessage("user", input)
                    )
                    try {
                        val aggregated = StringBuilder()
                        val aggregator = StreamAggregator(
                            onStreamStart = { p, m -> streamStart(p, m) },
                            onStreamDelta = { a, d -> streamDelta(a, d) },
                            onStreamEnd = { l, m -> streamEnd(l, m) }
                        )
                        aggregator.start(llmClient.describe(), null)
                        val resp = llmClient.chatStream(messages, onToken = { token ->
                            aggregated.append(token)
                            aggregator.accept(token)
                        })
                        aggregator.end(aggregated.length, resp.model)
                        // Single final state write (not per-token):
                        setState("stream_partial", aggregated.toString())
                        if (resp.usage != null) {
                            setState("llm_tokens", "${resp.usage.promptTokens}+${resp.usage.completionTokens}=${resp.usage.totalTokens}")
                        }
                        setState("llm_model", resp.model)
                        resp.content ?: buildResponse(input, intent, sentiment, toolResult)
                    } catch (e: LlmRateLimitException) {
                        setState("llm_error", "Rate limited (retry after ${e.retryAfter}s): ${e.message}")
                        buildResponse(input, intent, sentiment, toolResult)
                    } catch (e: LlmAuthException) {
                        setState("llm_error", "Auth failed: HTTP ${e.statusCode} — ${e.message}")
                        buildResponse(input, intent, sentiment, toolResult)
                    } catch (e: LlmNetworkException) {
                        setState("llm_error", "Network error: ${e.message}")
                        buildResponse(input, intent, sentiment, toolResult)
                    } catch (e: Exception) {
                        setState("llm_error", "${e::class.simpleName}: ${e.message}")
                        buildResponse(input, intent, sentiment, toolResult)
                    }
                } else {
                    buildResponse(input, intent, sentiment, toolResult)
                }

                setState("response_text", response)
                setState("response_tokens", response.length)
                setState("response_time", System.currentTimeMillis())

                registerDerived("response_summary", setOf("response_text", "response_tokens", "intent")) {
                    val text = getStateWithTracking("response_text") ?: ""
                    val tokens = getStateWithTracking("response_tokens") ?: 0
                    val intt = getStateWithTracking("intent") ?: "?"
                    "[$intt] $tokens chars: ${"$text".take(40)}..."
                }
            }

            step("output") {
                val text = getState<String>("response_text") ?: ""
                val summary = getState<String>("derived:response_summary") ?: ""
                // Only store output when we have real input (skip construction-time dry run)
                val input = getState<String>("input") ?: ""
                if (input.isNotEmpty()) {
                    setState("output", text)
                    setState("_meta", summary)
                    checkpoint("chat_${System.currentTimeMillis()}")
                }
            }
        }
    }

    fun debugger(): RuntimeDebugger = runtime.debugger()

    suspend fun chat(message: String, traceId: String? = null): ChatResult {
        val response = runtime.execute(AgentRequest(
            input = message,
            traceId = traceId
        ))
        val graph = debugger().timeline(response.traceId)
        return ChatResult(
            output = response.output,
            traceId = response.traceId,
            durationMs = response.durationMs,
            graph = graph
        )
    }

    private fun buildLlmSystemPrompt(intent: String, toolResult: String?): String {
        return buildString {
            appendLine("You are a debuggable AI agent running in Cogent, an observable execution runtime for JVM-native AI agents.")
            appendLine()
            appendLine("Your execution is fully traced — every step, state change, and tool call is recorded in a timeline DAG.")
            appendLine()
            appendLine("Current execution context:")
            appendLine("- Detected intent: $intent")
            if (toolResult != null) {
                appendLine("- Tool result: $toolResult")
            }
            appendLine()
            appendLine("Respond naturally and conversationally. Keep responses concise (2-4 sentences).")
            appendLine("Do not mention the execution runtime, timeline, or debugging unless asked about it.")
        }
    }

    private fun extractEntities(input: String): String {
        val entities = mutableListOf<String>()
        val words = input.split("\\s+".toRegex())
        words.forEach { word ->
            val clean = word.trim(',', '.', '!', '?')
            if (clean.length >= 3 && clean.first().isUpperCase()) {
                entities.add(clean)
            }
        }
        return if (entities.isEmpty()) "none" else entities.joinToString(", ")
    }

    private fun buildResponse(
        input: String,
        intent: String,
        sentiment: String,
        toolResult: String?
    ): String {
        val greeting = when (sentiment) {
            "positive" -> "Glad to help!"
            "negative" -> "Sorry about that."
            else -> "Hello!"
        }
        return when (intent) {
            "greeting" -> "$greeting I'm a debuggable chat agent. Try asking me something or type /help."
            "search" -> {
                val result = toolResult ?: "Nothing found."
                "$greeting Here's what I found:\n  $result"
            }
            "calculate" -> {
                val result = toolResult ?: "Couldn't compute that."
                "$greeting $result"
            }
            "question" -> {
                "$greeting That's an interesting question. I don't have a definitive answer yet, but I've logged it for analysis."
            }
            else -> {
                "$greeting You said: \"$input\"\nI've processed your message. Check the timeline below to see what happened."
            }
        }
    }
}

data class ChatResult(
    val output: String,
    val traceId: String,
    val durationMs: Long,
    val graph: TimelineGraph?
)
