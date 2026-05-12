# Cogent

> JVM-Native Agent Execution Protocol & Runtime

Cogent (formerly kagent-memory) is a Kotlin agent runtime inspired by Jetpack Compose and React Fiber. It brings UI-runtime abstractions — Snapshot, Derived State, Dependency Tracking, Fiber Scheduling — to the Agent state management domain, providing a unified execution protocol for JVM agents.

## Quick Start

```kotlin
// Create and run a runtime
val runtime = kAgentRuntime(id = "agent-1") {
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

// Query runtime state
println(runtime.state)                    // Completed
println(runtime.getState<String>("user")) // alice
println(runtime.getState<String>("derived:summary")) // alice is working on: greeting

// Take a snapshot
val snapshot = runtime.snapshot()

// View execution history
runtime.stateChanges().forEach { change ->
    println("${change.type}: ${change.key}")
}

// Replay to a checkpoint
runtime.replayToCheckpoint("init_done")
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                  Cogent Runtime (v0.4)               │
│              KAgentRuntime / Public API              │
├─────────────────────────────────────────────────────┤
│  Internal Subsystems (Hidden from Users):           │
│                                                     │
│  ┌──────────────┐    ┌────────────────────┐        │
│  │ AgentScheduler│◄──►│ RuntimeHeart       │        │
│  │ (Task Queue) │    │ (Orchestration)    │        │
│  └──────┬───────┘    └────────┬───────────┘        │
│         │                     │                     │
│  ┌──────┴───────┐    ┌────────┴───────────┐        │
│  │ Fiber Tasks  │    │ Dependency Tracker │        │
│  │ (Scheduling) │    │ (Auto-tracking)    │        │
│  └──────────────┘    └────────┬───────────┘        │
│                               │                     │
│  ┌────────────────────────────┴────────────┐       │
│  │           Memory (State Store)           │       │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ │       │
│  │  │ Snapshot │ │   Diff   │ │   LRU    │ │       │
│  │  └──────────┘ └──────────┘ └──────────┘ │       │
│  └─────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────┘
```

## Execution Model

```
memory.set(key, value)
    ↓
invalidate dependency graph
    ↓
schedule fiber task (priority-based)
    ↓
resume suspended coroutines
    ↓
recompute derived states
    ↓
emit events (Flow<AgentEvent>)
```

## API Reference

### KAgentRuntime

The only public entry point. All internal modules are hidden behind this facade.

#### Creation

```kotlin
// Simple factory
val runtime = kAgentRuntime(id = "my-agent") {
    step("init") {
        setState("key", "value")
    }
}

// With options
val runtime = kAgentRuntime(
    id = "my-agent",
    maxConcurrency = 4,
    memory = existingMemory
) {
    step("init") {
        setState("key", "value")
        checkpoint("init_done")
    }
    step("process") {
        // ...
    }
}

// DSL builder pattern
val runtime = kAgentRuntimeBuilder {
    id("my-agent")
    maxConcurrency(2)
    memory(existingMemory)
    run {
        step("init") {
            setState("key", "value")
        }
    }
}
```

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | `String` | Runtime identifier |
| `state` | `RuntimeState` | Current runtime state (Idle/Running/Suspended/Completed/Error) |
| `events` | `Flow<AgentEvent>` | Event stream for observing runtime activity |

#### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getState(): Map<String, Any?>` | All state key-value pairs |
| `getState<T>(key: String): T?` | Get specific state value |
| `stateChanges(): List<StateChange>` | Execution history timeline |
| `snapshot(): RuntimeSnapshot` | Take a snapshot of current state |
| `replayToCheckpoint(name: String)` | Restore state to named checkpoint |
| `cancel()` | Cancel the runtime |

### RuntimeHeartScope

The execution scope provided inside `kAgentRuntime {}` blocks.

#### Methods

| Method | Description |
|--------|-------------|
| `setState(key: String, value: Any?)` | Set a state value (triggers invalidation) |
| `getState<T>(key: String): T?` | Get a state value |
| `getStateWithTracking(key: String): Any?` | Get value with automatic dependency tracking |
| `step(id: String, priority: Int = 0) { }` | Define a schedulable execution step |
| `registerDerived(id: String, dependencies: Set<String>) { }` | Register a derived/computed state |
| `derivedSuspend(id: String) { }` | Create an async derived state |
| `checkpoint(name: String)` | Create a named checkpoint for replay |
| `replayToCheckpoint(name: String)` | Replay to a named checkpoint |

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

### AgentEvent

Events emitted through `runtime.events` Flow:

```kotlin
sealed class AgentEvent {
    data class TaskScheduled(val taskId: String) : AgentEvent()
    data class TaskStarted(val taskId: String) : AgentEvent()
    data class TaskCompleted(val taskId: String) : AgentEvent()
    data class TaskFailed(val taskId: String, val error: Throwable) : AgentEvent()
    data class TaskSuspended(val taskId: String) : AgentEvent()
    data class TaskResumed(val taskId: String) : AgentEvent()
    data class Invalidated(val key: String, val affectedTasks: Set<String>) : AgentEvent()
}
```

### StateChange

Records from `runtime.stateChanges()`:

```kotlin
data class StateChange(
    val type: StateChangeType,    // VALUE_SET, STEP_COMPLETE, STEP_FAIL, DERIVED_RECOMPUTE, CHECKPOINT, REPLAY, INVALIDATION
    val key: String,
    val oldValue: Any?,
    val newValue: Any?,
    val timestamp: Long,
    val metadata: Map<String, Any?>
)
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

## Internal Modules

The following modules are internal (`internal` visibility) and should not be used directly:

### AgentScheduler

Unified task scheduler with priority-based execution, worker pool, and event flow.

### RuntimeHeart

Orchestration layer that ties together memory, dependency tracking, and scheduling.

### Memory

Thread-safe state store with mutex-based synchronization, reactive StateFlow, LRU eviction, and snapshot/restore.

### DependencyTracker

Automatic dependency tracking using ThreadLocal observation context, similar to Compose's snapshot reads.

### InvalidationGraph

Manages dependency relationships and propagates invalidation events.

### DerivedState / SuspendableDerivedState

Computed states that automatically recompute when dependencies change.

## Version History

| Version | Description | Key Features |
|---------|-------------|--------------|
| v0.1 | MVP | Reactive Memory, Snapshot, Diff Engine, LRU Eviction, DSL Builder |
| v0.2 | Reactive Layer | Dependency Graph, Derived State, Recomposition Scope, Execution Trace, Replay Engine |
| v0.3 | Fiber Runtime | AgentScheduler, RuntimeHeart, Suspendable Derived, Auto Dependency Tracking, Fiber Tasks |
| v0.4 | API Convergence | KAgentRuntime unified facade, hidden internals, clean public API |
| v0.5 | Rebrand to Cogent | Package namespace `com.cogent`, project renamed |
| v0.5.1 | Execution Protocol | AgentRequest/Response, execute/stream dual API, RuntimeInterceptor, traceId, RuntimeEvent, 16 protocol tests |

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
