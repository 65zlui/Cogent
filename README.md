# Cogent

> JVM-Native Agent Execution Protocol & Runtime — Execution Observability Infrastructure

Cogent is a Kotlin agent runtime that brings UI-runtime abstractions — Snapshot, Derived State, Dependency Tracking, Fiber Scheduling — to the Agent state management domain. 

**v0.6** focuses on **execution observability infrastructure**: making agent execution observable, navigable, and analyzable through a DAG-based timeline model with graph-native query capabilities.  
**v0.7** (in progress) adds a **Visual Runtime Inspector** — a Compose-based desktop UI for timeline visualization, state inspection, and execution replay.

The project is organized as a **6-module Gradle project**:

| Module | Responsibility | Status |
|--------|---------------|--------|
| `kagent-core` | Runtime kernel (memory, fiber, trace) | ✅ Stable |
| `kagent-protocol` | Execution protocol (execute/stream/trace) | ✅ Stable |
| `kagent-debugger` | Debug & timeline analysis (DAG, query engine) | ✅ Maturing |
| `kagent-inspector` | Visual runtime inspector UI | 🧠 v0.7 |
| `kagent-examples` | Runnable agent examples (chat, tools, timeline) | 🔄 Evolving |
| `kagent-demo` | API walkthrough demo | 🔄 Evolving |

## Quick Start

```kotlin
// Create a runtime with v0.5 Execution Protocol
val runtime = kAgentRuntime(id = "agent-1") {
    run {
        step("initialize") {
            setState("user", "alice")
            setState("task", "greeting")
            checkpoint("init_done")
        }

        step("process") {
            registerDerived("summary", setOf("user", "task")) {
                val user = getStateWithTracking("user") ?: "unknown"
                val task = getStateWithTracking("task") ?: "none"
                "$user is working on: $task"
            }
        }
    }
}

// v0.5 Execution Protocol
val response = runtime.execute(AgentRequest(input = "start"))
println(response.output)

// Real-time event stream
runtime.stream(AgentRequest(input = "start")).collect { event ->
    when (event) {
        is RuntimeEvent.RunStart -> println("→ run started")
        is RuntimeEvent.StepStart -> println("  → step: ${event.stepId}")
        is RuntimeEvent.MemoryChange -> println("  → state: ${event.key} = ${event.newValue}")
        is RuntimeEvent.StepEnd -> println("  ← step: ${event.stepId}")
        is RuntimeEvent.RunEnd -> println("← run finished")
        else -> {}
    }
}

// v0.6 Timeline DAG — reconstruct execution as a causality graph
val dbg = runtime.debugger()
val graph = dbg.timeline(response.traceId)
graph?.edges?.forEach { edge ->
    println("${edge.type}: ${edge.fromNodeId} → ${edge.toNodeId}")
}

// v0.6.2 Queryable Execution Graph — navigate the DAG
val engine = dbg.query(response.traceId)
engine?.let { q ->
    val criticalPath = q.criticalPath()
    println("Critical path: ${criticalPath.size} nodes")

    val steps = q.byStep("process")
    println("Process step nodes: ${steps.size}")

    val firstNode = graph?.nodes?.firstOrNull()
    if (firstNode != null) {
        val descendants = q.descendants(firstNode.id)
        println("Descendants of root: ${descendants.size}")
    }
}
```

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│            kagent-inspector — Visual Runtime Inspector            │
│            (v0.7, Compose for Desktop / Skia)                     │
│  ┌────────────────┐ ┌─────────────────┐ ┌──────────────────────┐  │
│  │ Timeline DAG   │ │ State Inspector │ │ Replay Player        │  │
│  │ Graph Viewer   │ │ Live values at  │ │ Step-through         │  │
│  │ (zoom/pan/filter)││ any graph node │ │ execution replay     │  │
│  └───────┬────────┘ └───────┬─────────┘ └──────────┬───────────┘  │
│          │                  │                       │              │
│          └──────────────────┼───────────────────────┘              │
│                             │ debugs via                            │
└─────────────────────────────┼──────────────────────────────────────┘
                              │
┌─────────────────────────────┼──────────────────────────────────────┐
│              Cogent Runtime (v0.6)                                 │
│                   KAgentRuntime                                    │
├────────────────────────────────────────────────────────────────────┤
│  Execution Protocol              Observability                     │
│  ┌────────────────────┐         ┌────────────────────────────┐    │
│  │ execute()          │         │ RuntimeDebugger             │    │
│  │ stream() ──────────┼─events─→│  ├─ timeline(traceId) → DAG│    │
│  │ trace()            │         │  ├─ query(traceId) → engine│    │
│  └────────┬───────────┘         │  ├─ inspect(nodeId)        │    │
│           │                     │  ├─ children/parents       │    │
│           ▼                     │  └─ queryEvents(filter)    │    │
│  ┌──────────────────────────────┴─────────────────────┐         │    │
│  │          Observability Pipeline                     │         │    │
│  │  EventStore → TimelineProjection                    │         │    │
│  │    → TimelineGraph (SEQUENTIAL/CAUSAL/TOOL_FLOW)   │         │    │
│  │    → TimelineIndices → TimelineQueryEngine         │         │    │
│  └────────────────────────────────────────────────────┘         │    │
│                                                                   │    │
│  Internal Subsystems:                                             │    │
│  ┌──────────────┐  ┌────────────────┐  ┌────────────────────┐   │    │
│  │AgentScheduler│◄→│  RuntimeHeart   │  │   Memory           │   │    │
│  │ (Task Queue) │  │ (Orchestration) │  │ (State + Snapshot) │   │    │
│  └──────┬───────┘  └────────┬───────┘  └────────────────────┘   │    │
│         │                   │                                     │    │
│  ┌──────┴───────┐  ┌───────┴────────┐                            │    │
│  │ Fiber Tasks  │  │DependencyTracker│                            │    │
│  │ (Scheduling) │  │ (Auto-tracking)│                            │    │
│  └──────────────┘  └────────────────┘                            │    │
│                                                                   │    │
│  ┌────────────────────────────────────────────────────────┐      │    │
│  │  InvalidationGraph → DerivedState → Snapshot/Diff/Replay│      │    │
│  └────────────────────────────────────────────────────────┘      │    │
└────────────────────────────────────────────────────────────────────┘
```

## Observability Data Flow

```
RuntimeEvent (8 subtypes)
    │
    ▼
EventStore.record() → assigns stateVersion (monotonic)
    │
    ▼
TimelineProjection.project() → TimelineGraph + TimelineIndices
    │  ├── nodes: List<TimelineNode>
    │  ├── edges: List<TimelineEdge>
    │  │    ├── SEQUENTIAL (chronological ordering)
    │  │    ├── CAUSAL    (StepStart↔StepEnd, RunStart↔RunEnd)
    │  │    └── TOOL_FLOW (ToolCall↔ToolResult)
    │  └── indices: TimelineIndices (immutable, pre-built)
    │       ├── byNodeId         lookup by node ID
    │       ├── byStepId         index by step name
    │       ├── byTool           index by tool name
    │       ├── adjacency        forward edge map
    │       └── reverseAdjacency reverse edge map
    │
    ▼
TimelineQueryEngine → graph-native queries
    │  ├── byStep / byTool / byTimeRange
    │  ├── children / parents
    │  ├── descendants / ancestors
    │  ├── criticalPath (longest duration)
    │  └── filterByType (subgraph extraction)
    │
    ▼
RuntimeDebugger → unified API for query + inspect + navigate
    │
    ▼
kagent-inspector (v0.7) → Visual Runtime Inspector
    │  ├── Timeline DAG Viewer (interactive graph)
    │  ├── State Inspector (node-level state lookup)
    │  └── Replay Player (step-through execution)
```

## API Reference

### KAgentRuntime (v0.5 Execution Protocol)

The unified execution entry point.

#### Creation

```kotlin
// Simple factory
val runtime = kAgentRuntime(id = "my-agent") {
    run {
        step("init") { setState("key", "value") }
    }
}

// With options
val runtime = kAgentRuntime(id = "my-agent", maxConcurrency = 4, memory = existingMemory) {
    run {
        step("init") { /* ... */ }
    }
}

// DSL builder pattern
val runtime = kAgentRuntimeBuilder {
    id("my-agent")
    maxConcurrency(2)
    memory(existingMemory)
    run { step("main") { /* ... */ } }
}
```

#### Execution Protocol

| Method | Return Type | Description |
|--------|-------------|-------------|
| `execute(request)` | `AgentResponse` | Synchronous execution with traceId |
| `stream(request)` | `Flow<RuntimeEvent>` | Real-time event stream during execution |
| `trace(traceId)` | `List<RuntimeEvent>` | Retrieve stored events for a trace |

#### State Access

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getState()` | `Map<String, Any?>` | All state key-value pairs |
| `getState<T>(key)` | `T?` | Specific state value |
| `stateChanges()` | `List<StateChange>` | Execution history records |
| `snapshot()` | `RuntimeSnapshot` | Snapshot of current state |
| `replayToCheckpoint(name)` | `Unit` | Restore state to named checkpoint |
| `cancel()` | `Unit` | Cancel the runtime |
| `debugger()` | `RuntimeDebugger` | Access the observability debugger |

### RuntimeEvent

Unified observability event model (8 sealed subtypes):

```kotlin
sealed class RuntimeEvent {
    data class RunStart(val traceId: String)
    data class StepStart(val stepId: String)
    data class MemoryChange(val key: String, val oldValue: Any?, val newValue: Any?)
    data class DerivedRecompute(val key: String)
    data class ToolCall(val tool: String, val input: String)
    data class ToolResult(val tool: String, val output: String)
    data class StepEnd(val stepId: String)
    data class RunEnd(val traceId: String)
}
```

### RuntimeDebugger (v0.6+)

Execution observability & analysis tool, independent from the runtime execution path.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `timeline(traceId)` | `TimelineGraph?` | Reconstruct execution DAG with indices for a trace |
| `query(traceId)` | `TimelineQueryEngine?` | Access graph-native query engine for a trace (v0.6.2+) |
| `inspect(nodeId)` | `TimelineNode?` | Look up a node by ID across all cached traces (v0.6.2+) |
| `children(nodeId)` | `List<TimelineNode>` | Direct children of a node (forward adjacency, v0.6.2+) |
| `parents(nodeId)` | `List<TimelineNode>` | Direct parents of a node (reverse adjacency, v0.6.2+) |
| `inspectState(version)` | `Map<String, Any?>?` | State snapshot by version (v0.6.1+) |
| `queryEvents(filter)` | `List<RuntimeEvent>` | Filter events by trace/type/time |
| `traceIds()` | `List<String>` | All trace IDs in the event store |
| `eventCount()` | `Int` | Total stored events |

### TimelineGraph (v0.6.1 — Timeline DAG)

```kotlin
data class TimelineGraph(
    val traceId: String,
    val nodes: List<TimelineNode>,
    val edges: List<TimelineEdge>,
    val startTime: Long,
    val endTime: Long,
    val nodeCount: Int,
    val indices: TimelineIndices  // pre-built query indices (v0.6.2+)
)

data class TimelineNode(
    val id: String,
    val event: RuntimeEvent,
    val timestamp: Long,
    val traceId: String,
    val parentId: String?,
    val stateVersion: Long
)

enum class EdgeType { SEQUENTIAL, CAUSAL, TOOL_FLOW }

data class TimelineEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val type: EdgeType
)
```

### TimelineIndices (v0.6.2 — Immutable Query Indices)

Pre-computed indices built once during projection. Immutable after construction — all queries use these for O(1) or O(log n) access.

```kotlin
data class TimelineIndices(
    val byNodeId: Map<String, TimelineNode>,           // node ID → node
    val byStepId: Map<String, List<String>>,           // step name → node IDs
    val byTool: Map<String, List<String>>,             // tool name → node IDs
    val adjacency: Map<String, List<String>>,          // node ID → child IDs
    val reverseAdjacency: Map<String, List<String>>    // node ID → parent IDs
) {
    companion object {
        fun build(nodes: List<TimelineNode>, edges: List<TimelineEdge>): TimelineIndices
    }
}
```

### TimelineProjection (v0.6.2 — Production Entry Point)

Converts raw EventStore records into a fully-indexed, queryable TimelineGraph. The production entry point for timeline construction — delegates to TimelineBuilder for DAG construction, then builds indices.

```kotlin
class TimelineProjection {
    fun project(events: List<EventStoreEntry>): TimelineGraph?
}
```

### TimelineQueryEngine (v0.6.2 — Graph-Native Queries)

Query engine for navigating and analyzing an execution DAG. All queries use pre-built indices — no full-graph scans.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `byStep(stepId)` | `List<TimelineNode>` | Find all nodes for a step (StepStart + StepEnd) |
| `byTool(tool)` | `List<TimelineNode>` | Find all nodes for a tool (ToolCall + ToolResult) |
| `byTimeRange(start, end)` | `List<TimelineNode>` | Find nodes within a time range |
| `children(nodeId)` | `List<TimelineNode>` | Direct children (forward adjacency) |
| `parents(nodeId)` | `List<TimelineNode>` | Direct parents (reverse adjacency) |
| `descendants(nodeId)` | `List<TimelineNode>` | All descendants (recursive DFS) |
| `ancestors(nodeId)` | `List<TimelineNode>` | All ancestors (recursive DFS) |
| `criticalPath()` | `List<TimelineNode>` | Longest duration path via topological sort + DP |
| `filterByType(vararg types)` | `TimelineGraph` | Subgraph extraction by event type |

### ExecutionSpan (v0.6.2 — Placeholder)

Reserved for the v0.6.3+ tracing model (OpenTelemetry-style spans). Currently a placeholder data class.

```kotlin
data class ExecutionSpan(
    val startNode: String,
    val endNode: String,
    val durationMs: Long
)
```

### KAgentRuntimeScope (execution DSL)

| Method | Description |
|--------|-------------|
| `setState(key, value)` | Set state value (triggers invalidation) |
| `getState<T>(key)` | Get a state value |
| `getStateWithTracking(key)` | Get value with dependency tracking |
| `step(id, priority) { }` | Define a schedulable execution step |
| `registerDerived(id, deps) { }` | Register a derived/computed state |
| `derivedSuspend(id) { }` | Async derived state |
| `checkpoint(name)` | Create a named checkpoint for replay |
| `replayToCheckpoint(name)` | Replay to a named checkpoint |

### AgentRequest / AgentResponse

```kotlin
data class AgentRequest(
    val input: String,
    val context: Map<String, Any?> = emptyMap(),
    val sessionId: String? = null,
    val traceId: String? = null
)

data class AgentResponse(
    val output: String,
    val traceId: String,
    val durationMs: Long
)
```

### RuntimeState

```kotlin
sealed class RuntimeState {
    object Idle : RuntimeState()
    object Running : RuntimeState()
    object Suspended : RuntimeState()
    object Completed : RuntimeState()
    data class Error(val exception: Throwable) : RuntimeState()
}
```

### StateChange / StateChangeType

```kotlin
data class StateChange(
    val type: StateChangeType,
    val key: String,
    val oldValue: Any?,
    val newValue: Any?,
    val timestamp: Long,
    val metadata: Map<String, Any?>
)

enum class StateChangeType {
    VALUE_SET, STEP_COMPLETE, STEP_FAIL,
    DERIVED_RECOMPUTE, CHECKPOINT, REPLAY, INVALIDATION
}
```

### RuntimeSnapshot

```kotlin
data class RuntimeSnapshot(
    val id: String,
    val state: RuntimeState,
    val timestamp: Long,
    val states: Map<String, Any?>,
    val checkpointNames: List<String>
)
```

### RuntimeInterceptor (Protocol-Level Control Plane)

```kotlin
runtime.addRuntimeInterceptor { request, chain ->
    println("→ ${request.input}")
    val response = chain.proceed(request.copy(input = "modified: ${request.input}"))
    println("← ${response.output}")
    response
}
```

## Modules

The project is a **6-module Gradle project**. See the [Module Architecture](#architecture-overview) section above for the dependency graph and lifecycle alignment.

| Gradle module | Public API | Internal components |
|---------------|-----------|-------------------|
| `kagent-core` | — | `Memory`, `AgentScheduler`, `RuntimeHeart`, `DependencyTracker`, `InvalidationGraph`, `DerivedState` |
| `kagent-protocol` | `KAgentRuntime`, `kAgentRuntime()`, `AgentRequest/Response`, `RuntimeEvent`, `RuntimeState`, `StateChange`, `RuntimeSnapshot`, `StepInterceptor`, `RuntimeInterceptor` | `EventStore` |
| `kagent-debugger` | `RuntimeDebugger`, `TimelineGraph`, `TimelineNode`, `TimelineEdge`, `EdgeType`, `TimelineIndices`, `TimelineQueryEngine`, `EventFilter`, `ExecutionSpan` | `TimelineBuilder`, `TimelineProjection` |
| `kagent-inspector` | *(v0.7 — Visual Runtime Inspector)* | *(Compose/Skia Desktop UI)* |
| `kagent-examples` | `ChatAgent`, `ChatTool` | `ChatRunner` (interactive REPL) |
| `kagent-demo` | — | API walkthrough demo |

### Internal Runtime Components

These runtime internals are **not public API** and should not be used directly:

| Component | Module | Responsibility |
|-----------|--------|---------------|
| `RuntimeHeart` | `kagent-core` | Orchestration — ties memory, dependency tracking, and scheduling |
| `AgentScheduler` | `kagent-core` | Task scheduling with priority-based execution and worker pool |
| `Memory` | `kagent-core` | Thread-safe state store with snapshot/restore, LRU eviction |
| `DependencyTracker` | `kagent-core` | Automatic dependency tracking via ThreadLocal observation context |
| `InvalidationGraph` | `kagent-core` | Dependency relationship management and invalidation propagation |
| `DerivedState` | `kagent-core` | Computed states that auto-recompute on dependency changes |
| `EventStore` | `kagent-protocol` | Bounded, thread-safe event log with monotonic stateVersion |
| `TimelineBuilder` | `kagent-debugger` | Internal DAG constructor — builds TimelineGraph edges from flat events |
| `TimelineProjection` | `kagent-debugger` | Production entry point — TimelineBuilder + TimelineIndices.build() (v0.6.2+) |
| `TimelineQueryEngine` | `kagent-debugger` | Graph-native query interface — byStep/byTool/criticalPath/descendants (v0.6.2+) |

## v0.7 — Visual Runtime Inspector (In Progress)

The v0.7 release adds a **Compose for Desktop** visual inspector — a standalone desktop application that connects to the runtime debugger and provides interactive visualization of agent execution.

### Planned capabilities

| Feature | Description | Status |
|---------|-------------|--------|
| **Timeline DAG Viewer** | Interactive graph view of execution timeline. Zoom, pan, filter by event type. Nodes show step/tool/state-change events; edges show SEQUENTIAL/CAUSAL/TOOL_FLOW relationships. | 🧠 Planned |
| **State Inspector** | Select any graph node to inspect memory state at that point in the execution. Shows all key-value pairs with version and timestamp. | 🧠 Planned |
| **Replay Player** | Step-through execution replay — play/pause/seek across the timeline. Each step animates state changes in the inspector panel. | 🔬 Research |
| **Critical Path Highlight** | Automatically highlight the critical path (longest-duration chain) in the timeline graph. | 🧠 Planned |
| **Multi-trace Comparison** | Side-by-side comparison of multiple execution traces. | 🔬 Research |

### Architecture

The inspector lives in `kagent-inspector/` — a separate module with no reverse dependency on the runtime. It communicates with the runtime exclusively through the `RuntimeDebugger` API:

```
kagent-inspector (Compose UI)
    │  calls RuntimeDebugger API (via kagent-debugger module)
    ▼
RuntimeDebugger → TimelineQueryEngine → TimelineProjection → EventStore
```

This design ensures:
- **No UI dependency leaks** into the runtime kernel
- **Independent iteration** — the inspector can evolve at its own pace
- **Pluggable** — alternative inspectors (CLI, web, IDE plugin) can use the same debugger API

### Why Compose for Desktop

| Requirement | Choice |
|------------|--------|
| Rich graph rendering | Canvas API + custom layout engine |
| Cross-platform | Compose Desktop (macOS/Linux/Windows) |
| No JVM WebSocket server | Desktop app connects in-process |
| Familiar Kotlin API | No HTML/CSS/JS — pure Kotlin |
| Future Android support | Compose Multiplatform → shared UI code |

## Version History

| Version | Description | Key Features |
|---------|-------------|--------------|
| v0.1 | MVP | Reactive Memory, Snapshot, Diff Engine, LRU Eviction, DSL Builder |
| v0.2 | Reactive Layer | Dependency Graph, Derived State, Recomposition Scope, Execution Trace, Replay Engine |
| v0.3 | Fiber Runtime | AgentScheduler, RuntimeHeart, Suspendable Derived, Auto Dependency Tracking |
| v0.4 | API Convergence | KAgentRuntime unified facade, hidden internals, clean public API |
| v0.5 | Rebrand & Protocol | Package `com.cogent`, AgentRequest/Response, execute/stream/trace, RuntimeInterceptor, RuntimeEvent |
| v0.6 | Observability Plane | RuntimeDebugger, EventStore, stateVersion, EventFilter, queryEvents, trace() API |
| v0.6.1 | Timeline DAG | TimelineGraph, TimelineEdge, EdgeType (SEQUENTIAL/CAUSAL/TOOL_FLOW), causal linking, nested step pairing, filterByType |
| v0.6.2 | Queryable Execution Graph | TimelineProjection, TimelineIndices (immutable), TimelineQueryEngine (byStep/byTool/byTimeRange/descendants/ancestors/children/parents/criticalPath/filterByType), ExecutionSpan placeholder, expanded RuntimeDebugger API (query/inspect/children/parents) |
| v0.7 | Visual Runtime Inspector | *(in progress)* Timeline DAG Viewer, State Inspector, Replay Player, Critical Path Highlight |

## Building

```bash
# Build and test all modules
./gradlew clean test

# Run API walkthrough demo
./gradlew :kagent-demo:run

# Run debuggable chat agent (interactive)
./gradlew :kagent-examples:run

# Build JAR
./gradlew build
```

## Requirements

- Kotlin 2.3.0
- JVM 17+
- kotlinx-coroutines 1.10.2

## License

MIT
