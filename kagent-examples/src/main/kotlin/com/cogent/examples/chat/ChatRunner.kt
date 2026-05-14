package com.cogent.examples.chat

import com.cogent.runtime.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Interactive chat agent runner with per-turn timeline display.
 *
 * Shows the full observability pipeline for each message:
 *   response text → Timeline DAG → edge analysis → debugger stats
 *
 * Auto-detects LLM provider from environment variables:
 *   OPENAI_API_KEY     → OpenAI (gpt-4o-mini)
 *   DEEPSEEK_API_KEY   → DeepSeek (deepseek-chat)
 *   KIMI_API_KEY       → Kimi/Moonshot (moonshot-v1-8k)
 *
 * Falls back to built-in response templates if no API key is set.
 *
 * Built-in commands:
 *   /help              Show available commands
 *   /traces            List all execution trace IDs
 *   /timeline <id>     Show compact timeline DAG for a trace
 *   /timeline --verbose <id>  Show full timeline DAG with all nodes
 *   /inspect <nodeId>  Show node details
 *   /query <traceId>   Run query engine on a trace (descendants, criticalPath)
 *   /stats             Show debugger statistics
 *   /llm               Show LLM provider and model info
 *   /quit              Exit
 */
fun main() = runBlocking {
    // Auto-detect LLM provider from environment
    val llmClient = try {
        val provider = detectLlmProvider()
        if (provider != null) {
            println("  [LLM] Detected provider: ${provider.displayName} (${provider.defaultModel})")
            createLlmClient(provider)
        } else {
            println("  [LLM] No API key found. Using built-in response templates.")
            println("  [LLM] Set OPENAI_API_KEY, DEEPSEEK_API_KEY, or KIMI_API_KEY for LLM-powered responses.")
            null
        }
    } catch (e: Exception) {
        println("  [LLM] Warning: ${e.message}")
        println("  [LLM] Falling back to built-in response templates.")
        null
    }

    val agent = ChatAgent(
        id = "debuggable-chat",
        tools = mapOf(
            "search" to SearchTool(),
            "calculator" to CalculatorTool(),
            "summarize" to SummarizeTool()
        ),
        llmClient = llmClient
    )
    val dbg = agent.debugger()

    println()
    println("╔══════════════════════════════════════════════════════════════╗")
    println("║        Debuggable Chat Agent — Execution Observability      ║")
    println("║                                                              ║")
    println("║  Every message produces a full execution trace:              ║")
    println("║    understand → act → compose → output                      ║")
    println("║    + tool calls (search, calculator, summarize)             ║")
    println("║    + Timeline DAG with CAUSAL/TOOL_FLOW/STREAM_FLOW edges   ║")
    if (llmClient != null) {
        println("║    + LLM-powered responses via ${llmClient.describe().padEnd(43)}║")
    } else {
        println("║    + Built-in response templates (set OPENAI_API_KEY,      ║")
        println("║      DEEPSEEK_API_KEY, or KIMI_API_KEY for LLM)           ║")
    }
    println("║                                                              ║")
    println("║  Commands: /help  /traces  /timeline  /inspect  /query      ║")
    println("║              /stats  /llm  /quit                             ║")
    println("╚══════════════════════════════════════════════════════════════╝")
    println()

    var turn = 0
    var shouldExit = false
    while (!shouldExit && isActive) {
        print("You [turn ${++turn}]: ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.isEmpty()) {
            // Don't count empty input as a turn
            turn--
            println("  [empty input, skipping]")
            println()
            continue
        }

        // Handle commands
        if (input.startsWith("/")) {
            shouldExit = handleCommand(input, dbg, agent)
            continue
        }

        // Execute chat through the agent (30s timeout)
        try {
            val result = withTimeout(30_000L) {
                agent.chat(input)
            }
            println()

            // Print response
            println("Agent: ${result.output}")
            println()

            // Print timeline DAG (compact mode)
            printTimeline(result.graph, result.traceId, result.durationMs, verbose = false)
        } catch (_: TimeoutCancellationException) {
            println()
            println("  [Timeout: agent.chat() took longer than 30 seconds]")
            agent.runtime.cancel()
        }
        println()
    }

    println("\nSession ended. Total turns: $turn")
}

// ================================================================
// Timeline display
// ================================================================

/**
 * Check whether a RuntimeEvent is a "landmark" for compact display.
 * Landmarks are the significant boundaries: Run, Step, Tool, StreamStart/End.
 */
private fun RuntimeEvent.isLandmark(): Boolean = this is RuntimeEvent.RunStart ||
    this is RuntimeEvent.RunEnd ||
    this is RuntimeEvent.StepStart ||
    this is RuntimeEvent.StepEnd ||
    this is RuntimeEvent.ToolCall ||
    this is RuntimeEvent.ToolResult ||
    this is RuntimeEvent.StreamStart ||
    this is RuntimeEvent.StreamEnd

private fun printTimeline(graph: TimelineGraph?, traceId: String, durationMs: Long, verbose: Boolean = false) {
    if (graph == null) {
        println("  [no timeline data]")
        return
    }

    // In compact mode, count hidden nodes for the summary
    val totalNodes = graph.nodeCount
    val displayNodes = if (verbose) graph.nodes else graph.nodes.filter { it.event.isLandmark() }
    val hiddenCount = totalNodes - displayNodes.size
    val showCompact = !verbose && hiddenCount > 0

    println("╔══════════════════════════════════════════════════════════════╗")
    println("║  Timeline: ${traceId.padEnd(46)}║")
    if (showCompact) {
        println("║  ${displayNodes.size} landmarks shown, $hiddenCount hidden (${totalNodes} total nodes), ${graph.edges.size} edges  ║")
        println("║  ${graph.endTime - graph.startTime}ms span (execute: ${durationMs}ms)                         ║")
    } else {
        println("║  ${totalNodes} nodes, ${graph.edges.size} edges, ${graph.endTime - graph.startTime}ms span (execute: ${durationMs}ms)  ║")
    }
    println("╠══════════════════════════════════════════════════════════════╣")

    // Edge type breakdown
    val edgeBreakdown = graph.edges.groupBy { it.type }.mapValues { it.value.size }
    if (edgeBreakdown.isNotEmpty()) {
        println("║  Edge breakdown:                                            ║")
        edgeBreakdown.forEach { (type, count) ->
            println("║    ${type.name.padEnd(12)} ${"$count".padEnd(5)}                                        ║")
        }
    }

    // Node timeline
    println("╠══════════════════════════════════════════════════════════════╣")
    println("║  Execution timeline:                                         ║")
    if (showCompact) {
        // Compact mode: show only landmarks
        var streamDeltaCount = 0
        for (node in graph.nodes) {
            if (node.event is RuntimeEvent.StreamDelta) {
                streamDeltaCount++
                continue
            }
            if (node.event.isLandmark()) {
                streamDeltaCount = 0
                println("║    ${node.id.substringAfterLast("_").padEnd(4)} ${nodeLabel(node.event)}")
            }
        }
        // Collapse consecutive repetitive MemoryChange
        if (hiddenCount - streamDeltaCount > 0) {
            println("║    ... ${hiddenCount - streamDeltaCount} state changes hidden (use /timeline --verbose <id>)")
        }
    } else {
        // Verbose mode: show every node
        graph.nodes.forEach { node ->
            val label = nodeLabel(node.event)
            val depth = if (node.event is RuntimeEvent.MemoryChange ||
                node.event is RuntimeEvent.DerivedRecompute ||
                node.event is RuntimeEvent.StreamDelta
            ) "  " else ""
            println("║    $depth${node.id.substringAfterLast("_").padEnd(4)} $label")
        }
    }

    // Edge details (show all edges regardless of mode)
    println("╠══════════════════════════════════════════════════════════════╣")
    println("║  Edges:                                                      ║")
    graph.edges.take(12).forEach { edge ->
        val from = nodeAbbrev(graph, edge.fromNodeId)
        val to = nodeAbbrev(graph, edge.toNodeId)
        println("║    ${edge.type.name.padEnd(12)} ${from} → ${to}")
    }
    if (graph.edges.size > 12) {
        println("║    ... and ${graph.edges.size - 12} more edges")
    }

    println("╠══════════════════════════════════════════════════════════════╣")
    println("║  Debugger: agent.debugger().query(\"${traceId.take(16)}...\")          ║")
    println("║    engine.byStep(\"compose\")     engine.descendants(root)     ║")
    println("║    engine.criticalPath()        engine.filterByType(...)    ║")
    println("╚══════════════════════════════════════════════════════════════╝")
}

// ================================================================
// Command handler
// ================================================================

/**
 * Handle a user command. Returns true if the runner should exit.
 */
private fun handleCommand(input: String, dbg: RuntimeDebugger, agent: ChatAgent): Boolean {
    val parts = input.split("\\s+".toRegex(), limit = 3)
    val cmd = parts[0].lowercase()
    val flag = parts.getOrNull(1) ?: ""
    val arg = parts.getOrNull(2) ?: ""

    println()
    when (cmd) {
        "/help" -> {
            println("  Commands:")
            println("    /help              Show this help")
            println("    /traces            List all trace IDs")
            println("    /timeline <id>     Show compact timeline DAG")
            println("    /timeline --verbose <id>  Show full timeline with all nodes")
            println("    /inspect <nodeId>  Show node details")
            println("    /query <traceId>   Run query engine (descendants, criticalPath)")
            println("    /stats             Show debugger statistics")
            println("    /llm               Show LLM provider and model info")
            println("    /quit              Exit")
            println()
            println("  Agent behavior:")
            println("    - Type any message to chat")
            println("    - Use keywords like \"search <query>\", \"calc 2+2\",")
            println("      or \"summarize <text>\" to trigger tool execution")
        }

        "/traces" -> {
            val traces = dbg.traceIds()
            if (traces.isEmpty()) {
                println("  No traces recorded yet.")
            } else {
                println("  Traces (${traces.size}):")
                traces.forEach { t -> println("    $t") }
            }
        }

        "/timeline" -> {
            val verbose = flag == "--verbose"
            val traceId = if (verbose) arg else flag
            if (traceId.isBlank()) {
                println("  Usage: /timeline [--verbose] <traceId>")
                println("  Tip: use /traces to list available trace IDs")
            } else {
                val graph = dbg.timeline(traceId)
                if (graph == null) {
                    println("  No timeline found for trace: $traceId")
                } else {
                    printTimeline(graph, traceId, graph.endTime - graph.startTime, verbose)
                }
            }
        }

        "/inspect" -> {
            val nodeId = flag // arg is at position 1 for 2-part commands
            if (nodeId.isBlank()) {
                println("  Usage: /inspect <nodeId>")
                println("  Tip: use /timeline <traceId> to see node IDs")
            } else {
                val node = dbg.inspect(nodeId)
                if (node == null) {
                    println("  No node found: $nodeId")
                } else {
                    println("  Node: ${node.id}")
                    println("  Event: ${node.event::class.simpleName}")
                    println("  Timestamp: ${node.timestamp}")
                    println("  Trace: ${node.traceId}")
                    println("  Parent: ${node.parentId ?: "none"}")
                    println("  State version: ${node.stateVersion}")
                    when (val e = node.event) {
                        is RuntimeEvent.StepStart -> println("  Step: ${e.stepId}")
                        is RuntimeEvent.StepEnd -> println("  Step: ${e.stepId}")
                        is RuntimeEvent.MemoryChange -> println("  Key: ${e.key}, Old: ${e.oldValue}, New: ${e.newValue}")
                        is RuntimeEvent.ToolCall -> println("  Tool: ${e.tool}, Input: ${e.input}")
                        is RuntimeEvent.ToolResult -> println("  Tool: ${e.tool}, Output: ${e.output}")
                        is RuntimeEvent.DerivedRecompute -> println("  Key: ${e.key}")
                        is RuntimeEvent.StreamStart -> println("  Provider: ${e.provider}, Model: ${e.model}")
                        is RuntimeEvent.StreamDelta -> println("  Accumulated: ${e.accumulated.length} chars, Delta: ${e.deltaContent.length} chars")
                        is RuntimeEvent.StreamEnd -> println("  Total: ${e.totalLength} chars, Model: ${e.model}")
                        else -> {}
                    }
                    // Show children/parents
                    val children = dbg.children(node.id)
                    val parents = dbg.parents(node.id)
                    if (parents.isNotEmpty()) {
                        println("  Parents (${parents.size}): ${parents.joinToString { it.id.substringAfterLast("_") }}")
                    }
                    if (children.isNotEmpty()) {
                        println("  Children (${children.size}): ${children.joinToString { it.id.substringAfterLast("_") }}")
                    }
                }
            }
        }

        "/query" -> {
            val traceId = flag
            if (traceId.isBlank()) {
                println("  Usage: /query <traceId>")
                println("  Tip: use /traces to list available trace IDs")
            } else {
                val engine = dbg.query(traceId)
                if (engine == null) {
                    println("  No query engine available for trace: $traceId")
                } else {
                    val graph = dbg.timeline(traceId)
                    println("  QueryEngine for trace: $traceId")
                    println()

                    // By step
                    val stepNames = listOf("understand", "act", "compose", "output")
                    stepNames.forEach { step ->
                        val nodes = engine.byStep(step)
                        println("    byStep(\"$step\"): ${nodes.size} nodes")
                    }

                    // Descendants from root
                    if (graph != null && graph.nodes.isNotEmpty()) {
                        val rootId = graph.nodes.first().id
                        val desc = engine.descendants(rootId)
                        println("    descendants(root): ${desc.size} nodes reachable")

                        // Critical path
                        val critical = engine.criticalPath()
                        println("    criticalPath(): ${critical.size} nodes")
                        critical.forEach { n ->
                            println("      ${n.id.substringAfterLast("_")}  ${n.event::class.simpleName}")
                        }

                        // Filter by type
                        val filtered = engine.filterByType(
                            RuntimeEvent.StepStart::class.java,
                            RuntimeEvent.StepEnd::class.java
                        )
                        println("    filterByType(StepStart/End): ${filtered.nodeCount} nodes, ${filtered.edges.size} edges")
                    }
                }
            }
        }

        "/stats" -> {
            println("  Debugger stats:")
            println("    traceIds:    ${dbg.traceIds().size}")
            println("    eventCount:  ${dbg.eventCount()}")
            println("    agent id:    ${agent.id}")
        }

        "/llm" -> {
            val desc = agent.llmClient?.describe() ?: "not configured"
            println("  LLM status:")
            println("    provider:    $desc")
            println("    configure:   set OPENAI_API_KEY, DEEPSEEK_API_KEY, or KIMI_API_KEY")
            println("    tokens:      run a chat and check timeline for llm_tokens state")
        }

        "/quit", "/exit" -> {
            println("  Goodbye!")
            return true
        }

        else -> {
            println("  Unknown command: $cmd")
            println("  Try: /help")
        }
    }
    println()
    return false
}

// ================================================================
// Helpers
// ================================================================

private fun nodeLabel(event: RuntimeEvent): String = when (event) {
    is RuntimeEvent.RunStart -> "RunStart"
    is RuntimeEvent.RunEnd -> "RunEnd"
    is RuntimeEvent.StepStart -> "StepStart(${event.stepId})"
    is RuntimeEvent.StepEnd -> "StepEnd(${event.stepId})"
    is RuntimeEvent.MemoryChange -> "  ${event.key}: ${event.oldValue} → ${event.newValue}"
    is RuntimeEvent.DerivedRecompute -> "  Derived: ${event.key}"
    is RuntimeEvent.ToolCall -> "ToolCall(${event.tool})"
    is RuntimeEvent.ToolResult -> "ToolResult(${event.tool})"
    is RuntimeEvent.StreamStart -> "StreamStart(provider=${event.provider}, model=${event.model})"
    is RuntimeEvent.StreamDelta -> "  StreamDelta(+${event.deltaContent.length} chars, total=${event.accumulated.length})"
    is RuntimeEvent.StreamEnd -> "StreamEnd(${event.totalLength} chars, model=${event.model})"
}

private fun nodeAbbrev(graph: TimelineGraph, nodeId: String): String {
    val node = graph.indices.byNodeId[nodeId] ?: return nodeId.substringAfterLast("_")
    return when (val e = node.event) {
        is RuntimeEvent.StepStart -> "SS(${e.stepId})"
        is RuntimeEvent.StepEnd -> "SE(${e.stepId})"
        is RuntimeEvent.ToolCall -> "TC(${e.tool})"
        is RuntimeEvent.ToolResult -> "TR(${e.tool})"
        is RuntimeEvent.MemoryChange -> "MC(${e.key})"
        is RuntimeEvent.DerivedRecompute -> "DR(${e.key})"
        is RuntimeEvent.RunStart -> "RUN_START"
        is RuntimeEvent.RunEnd -> "RUN_END"
        is RuntimeEvent.StreamStart -> "STR_START"
        is RuntimeEvent.StreamDelta -> "STR_DELTA"
        is RuntimeEvent.StreamEnd -> "STR_END"
    }
}
