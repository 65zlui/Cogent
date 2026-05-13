# Cogent

> JVM-Native Agent Execution Protocol & Runtime — Execution Observability Infrastructure

Cogent is a Kotlin agent runtime that brings UI-runtime abstractions — Snapshot, Derived State, Dependency Tracking, Fiber Scheduling — to the Agent state management domain. v0.6+ focuses on **execution observability infrastructure**: making agent execution observable, navigable, and analyzable through a DAG-based timeline model.

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
```

## Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│              Cogent Runtime (v0.6)                       │
│                   KAgentRuntime                          │
├──────────────────────────────────────────────────────────┤
│  Execution Protocol              Observability           │
│  ┌────────────────────┐         ┌──────────────────────┐ │
│  │ execute()          │         │ RuntimeDebugger       │ │
│  │ stream() ──────────┼─events─→│  ├─ timeline(traceId) │ │
│  │ trace()            │         │  ├─ queryEvents()     │ │
│  └────────┬───────────┘         │  └─ inspectState(v)   │ │
│           │                     └──────────┬───────────┘ │
│           ▼                                 ▼             │
│  ┌──────────────────────────────────────────────────┐    │
│  │              EventStore + TimelineBuilder         │    │
│  │  RuntimeEvent → EventStoreEntry → TimelineGraph  │    │
│  │  (stateVersion, edges: SEQUENTIAL/CAUSAL/TOOL)   │    │
│  └──────────────────────────────────────────────────┘    │
│                                                          │
│  Internal Subsystems:                                    │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐ │
│  │AgentScheduler│◄→│  RuntimeHeart   │  │   Memory     │ │
│  │ (Task Queue) │  │ (Orchestration) │  │ (State Store)│ │
│  └──────┬───────┘  └────────┬───────┘  └──────┬───────┘ │
│         │                   │                  │         │
│  ┌──────┴───────┐  ┌───────┴────────┐         │         │
│  │ Fiber Tasks  │  │DependencyTracker│         │         │
│  │ (Scheduling) │  │ (Auto-tracking)│         │         │
│  └──────────────┘  └────────────────┘         │         │
│                                                │         │
│  ┌─────────────────────────────────────────────┘         │
│  │  InvalidationGraph → DerivedState → Snapshot/Diff     │
│  └───────────────────────────────────────────────────────┘
└──────────────────────────────────────────────────────────┘
```

## Observability Data Flow

```
RuntimeEvent (8 subtypes)
    │
    ▼
EventStore.record() → assigns stateVersion (monotonic)
    │
    ▼
TimelineBuilder.build() → TimelineGraph
    │  ├── nodes: List<TimelineNode>
    │  └── edges: List<TimelineEdge>
    │       ├── SEQUENTIAL (chronological ordering)
    │       ├── CAUSAL    (StepStart↔StepEnd, RunStart↔RunEnd)
    │       └── TOOL_FLOW (ToolCall↔ToolResult)
    │
    ▼
RuntimeDebugger → query + inspect + navigate
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
| `timeline(traceId)` | `TimelineGraph?` | Reconstruct execution DAG for a trace |
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
    val nodeCount: Int
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

## Internal Modules

The following modules are `internal` and should not be used directly:

| Module | Responsibility |
|--------|---------------|
| `RuntimeHeart` | Orchestration — ties memory, dependency tracking, and scheduling |
| `AgentScheduler` | Task scheduling with priority-based execution and worker pool |
| `Memory` | Thread-safe state store with snapshot/restore, LRU eviction |
| `DependencyTracker` | Automatic dependency tracking via ThreadLocal observation context |
| `InvalidationGraph` | Dependency relationship management and invalidation propagation |
| `DerivedState` | Computed states that auto-recompute on dependency changes |
| `EventStore` | Bounded, thread-safe event log with monotonic stateVersion |
| `TimelineBuilder` | Reconstructs TimelineGraph from raw EventStore records |

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

## Building

```bash
# Build and test
./gradlew clean test

# Run demo
./gradlew run

# Build JAR
./gradlew build
```

## Requirements

- Kotlin 2.3.0
- JVM 17+
- kotlinx-coroutines 1.10.2

## License

MIT
