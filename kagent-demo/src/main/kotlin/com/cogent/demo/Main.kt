package com.cogent.demo

import com.cogent.runtime.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Cogent Demo — Execution Observability Walkthrough
 *
 * Showcases the full pipeline:
 *   v0.5 Execution Protocol  →  v0.6 Timeline DAG  →  v0.6.2 Query Engine
 */
fun main() = runBlocking {
    println("╔══════════════════════════════════════════════════════════╗")
    println("║         Cogent — Execution Observability Demo           ║")
    println("╚══════════════════════════════════════════════════════════╝")
    println()

    // ================================================================
    // 1. Create runtime with steps
    // ================================================================
    println("── 1. Create Runtime ──────────────────────────────────────")
    println("    kAgentRuntime(id = \"cogent-demo\") { ... }")
    val runtime = kAgentRuntime(id = "cogent-demo") {
        step("fetch") {
            setState("input", "hello world")
            setState("model", "gpt-4")
        }

        step("process", priority = 50) {
            val input = getState<String>("input") ?: ""
            setState("processed", input.uppercase())
            setState("tokens", input.length * 4)
            registerDerived("status", setOf("processed", "tokens")) {
                val text = getStateWithTracking("processed") ?: ""
                val count = getStateWithTracking("tokens") ?: 0
                "OK ($count tokens: $text)"
            }
        }

        step("output", priority = 10) {
            val status = getState<String>("derived:status") ?: "?"
            setState("output", status)
        }
    }
    println("    state: ${runtime.state}")
    println()

    // ================================================================
    // 2. v0.5 Execution Protocol — execute()
    // ================================================================
    println("── 2. v0.5 Execution Protocol ─────────────────────────────")
    println("    runtime.execute(AgentRequest(input = \"start\"))")
    val response = runtime.execute(AgentRequest(input = "start"))
    println("    traceId:   ${response.traceId}")
    println("    output:    \"${response.output}\"")
    println("    duration:  ${response.durationMs}ms")
    println()

    // ================================================================
    // 3. v0.5 Event Stream
    // ================================================================
    println("── 3. v0.5 Event Stream ───────────────────────────────────")
    println("    runtime.stream(AgentRequest(input = \"run-2\"))")
    val events = runtime.stream(AgentRequest(input = "run-2")).toList()
    events.forEach { event ->
        when (event) {
            is RuntimeEvent.RunStart -> println("    ▶ RUN START     ${event.traceId.take(20)}...")
            is RuntimeEvent.StepStart -> println("    │ STEP START    ${event.stepId}")
            is RuntimeEvent.StepEnd -> println("    │ STEP END      ${event.stepId}")
            is RuntimeEvent.MemoryChange -> println("    │   STATE       ${event.key}: ${event.oldValue} -> ${event.newValue}")
            is RuntimeEvent.DerivedRecompute -> println("    │   DERIVED     ${event.key}")
            is RuntimeEvent.RunEnd -> println("    ■ RUN END      ${event.traceId.take(20)}...")
            else -> {}
        }
    }
    println()

    // ================================================================
    // 4. v0.6.1 Timeline DAG
    // ================================================================
    println("── 4. v0.6.1 Timeline DAG ─────────────────────────────────")
    println("    runtime.debugger().timeline(traceId)")
    val dbg = runtime.debugger()
    val graph = dbg.timeline(response.traceId)
    if (graph != null) {
        println("    ${graph.nodeCount} nodes, ${graph.edges.size} edges, ${graph.endTime - graph.startTime}ms span")
        println()
        println("    Edge breakdown:")
        graph.edges.groupBy { it.type }.forEach { (type, edges) ->
            println("      ${type.name.padEnd(12)} ${edges.size}")
        }
        println()
        println("    -- Node timeline --")
        graph.nodes.forEach { node ->
            val label = when (val e = node.event) {
                is RuntimeEvent.RunStart -> "RunStart"
                is RuntimeEvent.RunEnd -> "RunEnd"
                is RuntimeEvent.StepStart -> "StepStart(${e.stepId})"
                is RuntimeEvent.StepEnd -> "StepEnd(${e.stepId})"
                is RuntimeEvent.MemoryChange -> "  MemoryChange(${e.key})"
                is RuntimeEvent.DerivedRecompute -> "  DerivedRecompute(${e.key})"
                is RuntimeEvent.ToolCall -> "ToolCall(${e.tool})"
                is RuntimeEvent.ToolResult -> "ToolResult(${e.tool})"
            }
            println("    ${node.id.substringAfterLast("_").padEnd(4)} $label")
        }
    }
    println()

    // ================================================================
    // 5. v0.6.2 TimelineQueryEngine
    // ================================================================
    println("── 5. v0.6.2 Query Engine ─────────────────────────────────")
    println("    runtime.debugger().query(traceId)")
    val engine = dbg.query(response.traceId)
    if (engine != null) {
        val processNodes = engine.byStep("process")
        println("    byStep(\"process\"):           ${processNodes.size} nodes")
        processNodes.forEach { n -> println("      ${n.id.substringAfterLast("_")}  ${n.event::class.simpleName}") }

        val root = graph!!.nodes.first()
        val desc = engine.descendants(root.id)
        println("    descendants(root):           ${desc.size} nodes reachable")

        val critical = engine.criticalPath()
        println("    criticalPath():              ${critical.size} nodes")
        critical.forEach { n ->
            println("      ${n.id.substringAfterLast("_")}  ${n.event::class.simpleName}")
        }

        val filtered = engine.filterByType(
            RuntimeEvent.StepStart::class.java,
            RuntimeEvent.StepEnd::class.java
        )
        println("    filterByType(StepStart/End): ${filtered.nodeCount} nodes, ${filtered.edges.size} edges")
    }
    println()

    // ================================================================
    // 6. v0.6.2 RuntimeDebugger
    // ================================================================
    println("-- 6. v0.6.2 Debugger API ---------------------------------")
    println("    traceIds():          ${dbg.traceIds()}")
    println("    eventCount():        ${dbg.eventCount()}")
    println("    inspectState(1):     ${dbg.inspectState(1)}")
    val firstId = graph?.nodes?.first()?.id
    if (firstId != null) {
        println("    inspect(root):        ${dbg.inspect(firstId)?.let { it.event::class.simpleName }}")
        println("    children(root):       ${dbg.children(firstId).size} direct children")
        println("    parents(root):        ${dbg.parents(firstId).size} parents")
    }
    println()

    // ================================================================
    // 7. Architecture overview
    // ================================================================
    println("╔══════════════════════════════════════════════════════════╗")
    println("║  Pipeline                                                 ║")
    println("║                                                          ║")
    println("║  RuntimeEvent -> EventStore -> TimelineProjection        ║")
    println("║    -> TimelineGraph (indices) -> TimelineQueryEngine    ║")
    println("║    -> RuntimeDebugger                                     ║")
    println("║                                                          ║")
    println("║  Modules:                                                ║")
    println("║    kagent-core       runtime kernel (memory/fiber/trace)   ║")
    println("║    kagent-protocol    execute/stream/trace                 ║")
    println("║    kagent-debugger    timeline/query/debug APIs            ║")
    println("║    kagent-inspector   visual UI (v0.7)                    ║")
    println("╚══════════════════════════════════════════════════════════╝")
}
