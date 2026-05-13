# 🚀 Roadmap

> Execution infrastructure for observable AI agents.

Cogent is not "yet another agent framework." It is a **reactive execution runtime** — inspired by Compose Runtime, React Fiber, and the Chrome DevTools protocol — applied to AI agent state management and observability.

The roadmap follows one consistent trajectory:

```
runtime → protocol → debugger → visual runtime
```

Each version adds a layer of infrastructure, not another surface API.

---

## ✅ v0.1 — Reactive Memory Runtime

*Reactive memory store with snapshot/diff and replay.*

| What | Why |
|------|-----|
| Reactive memory store | State changes propagate automatically |
| Snapshot / diff engine | Cheap state comparison and rollback |
| Replay support | Deterministic state reconstruction |
| DSL runtime builder | Declarative runtime configuration |

**Outcome:** A state management kernel that behaves like Compose `mutableStateOf` — but at agent scale.

---

## ✅ v0.2 — Dependency Graph Runtime

*Automatic dependency tracking with derived state and invalidation.*

| What | Why |
|------|-----|
| Dependency tracking | Know what depends on what |
| Derived state recomputation | Auto-update computed values on dependency change |
| Invalidation propagation | Cascade invalidations through the dependency graph |
| Execution tracing | Trace which states were read/written during execution |

**Outcome:** The runtime knows *why* every state change happened — a prerequisite for replay and debugging.

---

## ✅ v0.3 — Fiber Runtime

*Coroutine-based scheduling with runtime orchestration.*

| What | Why |
|------|-----|
| AgentScheduler | Priority-based step execution with worker pool |
| RuntimeHeart orchestration | Central execution loop coordinating memory, deps, and scheduler |
| Suspendable derived states | Derived computations that can suspend (e.g., tool calls) |
| Auto dependency tracking | ThreadLocal observation context — zero boilerplate for users |

**Outcome:** Concurrent, non-blocking execution with automatic dependency collection.

---

## ✅ v0.4 — Runtime API Convergence

*Unified facade over the internal kernel.*

| What | Why |
|------|-----|
| `KAgentRuntime` | Single entry point for all runtime operations |
| Hidden internal kernel | Internal modules are `internal` — not public API |
| Public runtime facade | Clean surface area: execute, stream, state access, debugger |
| OkHttp-style `StepInterceptor` | Step-level interception for instrumentation |

**Outcome:** A clean API boundary. Internal kernel can evolve without breaking users.

---

## ✅ v0.5 — Execution Protocol Layer

*Standardized execution protocol with traceId-based observability.*

| What | Why |
|------|-----|
| `execute()` / `stream()` | Synchronous + streaming execution paths |
| `AgentRequest` / `AgentResponse` | Canonical protocol types |
| `traceId`-based observability | Every execution has a traceable identity |
| `RuntimeEvent` unified event model | Single sealed hierarchy for all execution events |
| Runtime-level interceptors | OkHttp-style `InterceptorChain` at the protocol boundary |

**Outcome:** Execution becomes a first-class observable protocol — not a function call.

---

## 🔥 v0.6 — Runtime Debugger

*The observability plane. Execution becomes queryable.*

### v0.6.1 — Timeline Engine

| What | Why |
|------|-----|
| Timeline projection | Reconstruct execution timeline from flat event log |
| Causality graph | DAG with SEQUENTIAL / CAUSAL / TOOL_FLOW edges |
| Step↔Tool linking | Stack-based pairing of StepStart↔StepEnd, ToolCall↔ToolResult |
| Timeline DAG | Typed edges replace flat event list |

**Outcome:** Events become a navigable graph.

### v0.6.2 — Queryable Debugger

| What | Why |
|------|-----|
| Timeline query engine | Graph-native queries: byStep, byTool, byTimeRange |
| Causal traversal | descendants, ancestors, children, parents |
| Graph inspection API | inspect(nodeId), query(traceId) |
| Event filtering / indexing | Immutable indices at projection time — no full-graph scans |
| Critical path analysis | Longest duration path via topological sort + DP |

**Outcome:** The debugger is a queryable execution graph — not a log viewer.

---

## 🧠 Planned

### v0.7 — Visual Runtime Inspector

| What | Why |
|------|-----|
| Timeline UI | Interactive DAG visualization |
| Replay visualization | Step-through execution replay in the browser |
| Execution graph explorer | Tree + graph views of agent execution |
| Runtime state inspector | Live state inspection at any point in the timeline |

**Outcome:** A Chrome DevTools-like inspector for agent execution — but at the runtime level, not the network level.

### 🔬 Research

| Topic | Goal |
|-------|------|
| Deterministic replay | Replay any execution to exactly the same state |
| Runtime recording | Record full execution to a file — inspect offline |
| Scheduler ordering capture | Capture fiber schedule order for replay fidelity |
| Tool-call replay injection | Replay tool calls with recorded results |

---

## ❌ Deliberately NOT Building

Cogent is **not** trying to be:

- Another LangChain clone
- Another prompt wrapper
- Another workflow builder / DAG orchestrator
- Another MCP shell / tool-use framework
- An "OpenAI SDK wrapper"
- A "vector database" or "RAG pipeline"

### Why

The AI agent ecosystem already has:

- **Plenty** of high-level orchestration frameworks (LangChain, CrewAI, AutoGen)
- **Plenty** of prompt management tools
- **Plenty** of RAG pipelines
- **Plenty** of MCP servers

What it **doesn't** have is:

- A **reactive execution runtime** with deterministic replay
- An **observability infrastructure** at the runtime level
- A **queryable execution graph** — not log aggregation
- A **Compose-like state model** for agent memory

That gap is what Cogent fills.

---

## The Arc

```
v0.1  ─►  v0.2  ─►  v0.3  ─►  v0.4  ─►  v0.5  ─►  v0.6  ─►  v0.7
 │         │         │         │         │         │         │
 reactive  dep       fiber    API       protocol  debugger  visual
 memory    graph     runtime  facade    layer     engine    inspector
```

The runtime starts as a state management kernel and evolves into an observable execution infrastructure. Each layer is infrastructure — not application surface area.

---

## Module Architecture

As of v0.6.2, the project is structured as a **5-module Gradle project** with clean separation across lifecycle and dependency boundaries:

```
cogent/
├── kagent-core/          # Runtime kernel (zero UI deps)
│   └── com.cogent.{memory, fiber, trace}
├── kagent-protocol/      # Execution protocol (execute/stream/trace)
│   └── com.cogent.runtime  {ExecutionProtocol, KAgentRuntime, EventStore, ...}
├── kagent-debugger/      # Debug & analysis (timeline/query/debug APIs)
│   └── com.cogent.runtime  {RuntimeDebugger, Timeline*, TimelineQueryEngine}
├── kagent-inspector/     # Visual inspector UI (v0.7+, empty placeholder)
│   └── com.cogent.inspector
└── kagent-demo/          # Demo applications
    └── com.cogent.demo
```

### Module dependency graph

```
kagent-core
    ↑            (memory, fiber, trace — pure kernel)
kagent-protocol
    ↑            (execute/stream/trace types + KAgentRuntime facade)
kagent-debugger
    ↑            (Timeline*, TimelineQueryEngine, RuntimeDebugger)
kagent-inspector          (Compose-based UI, v0.7+)
kagent-demo               (standalone demos)
```

### Why separate modules

| Concern | Module | Iteration pace | Dependencies |
|---------|--------|---------------|--------------|
| Runtime kernel | `kagent-core` | Slow, stable | kotlinx-coroutines only |
| Execution protocol | `kagent-protocol` | Moderate | `kagent-core` + coroutines |
| Debug/timeline | `kagent-debugger` | Moderate | `kagent-protocol` + `kagent-core` |
| Visual tooling | `kagent-inspector` | Fast, experimental | Compose, Skia, Desktop |
| Demos | `kagent-demo` | Per-demo | `kagent-debugger` + coroutines |

**Tooling and runtime have fundamentally different lifecycles.** The inspector (v0.7+) will iterate fast with UI experiments; the core kernel must remain stable and dependency-light. This separation prevents Compose/Skia/Desktop dependencies from leaking into the runtime.

### Circular dependency resolution

The split between `kagent-protocol` and `kagent-debugger` creates an interesting circular dependency:

```
KAgentRuntime.debugger() → constructs RuntimeDebugger(eventStore, memory)
RuntimeDebugger          → depends on EventStore (in kagent-protocol)
```

This is resolved by the **extension function pattern**:

- `kagent-protocol` defines `KAgentRuntime` with `eventStore` and `getDebuggerMemory()` as public API
- `kagent-debugger` defines `fun KAgentRuntime.debugger(): RuntimeDebugger` as a top-level extension function
- Consumers call `runtime.debugger()` as if it were a member function — the implementation is resolved at compile time from the debugger module's classpath

This keeps the dependency unidirectional: `kagent-core ← kagent-protocol ← kagent-debugger`.

### Extension point design

The `KAgentRuntime` class exposes two key hooks for out-of-module extension:

```kotlin
// In kagent-protocol:
val eventStore: EventStore           // event storage for trace/query
fun getDebuggerMemory(): Memory      // memory state for inspection

// In kagent-debugger (extension function):
fun KAgentRuntime.debugger(): RuntimeDebugger  // debugger access
```

Any future module (profiler, audit logger, metrics collector) can follow the same pattern without modifying the runtime kernel.

## System Architecture

```
 ┌─────────────────────────────────────────────────────────────────────┐
 │                         kagent-demo                                │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │  Demo: Observability Pipeline Walkthrough                    │  │
 │  │  execute → stream → timeline → query → inspect → debug       │  │
 │  └──────────────────────────────────────────────────────────────┘  │
 └─────────────────────────────────────────────────────────────────────┘
                                    │ uses
 ┌──────────────────────────────────┼──────────────────────────────────┐
 │                    kagent-inspector (v0.7)     │                    │
 │  ┌─────────────────────────────────────────────────────────────┐   │
 │  │  Visual Runtime Inspector (Compose/Skia Desktop)            │   │
 │  │                                                             │   │
 │  │  ┌─────────────────┐ ┌──────────────┐ ┌──────────────────┐ │   │
 │  │  │ Timeline DAG    │ │ State        │ │ Replay           │ │   │
 │  │  │ Graph Viewer    │ │ Inspector    │ │ Player           │ │   │
 │  │  │ (zoom/pan/filter)│ │ (live values)│ │ (step-through)  │ │   │
 │  │  └────────┬────────┘ └──────┬───────┘ └────────┬─────────┘ │   │
 │  │           │                 │                   │           │   │
 │  │           └─────────────────┼───────────────────┘           │   │
 │  │                             │ queries via                    │   │
 │  └─────────────────────────────┼───────────────────────────────┘   │
 └─────────────────────────────────┼──────────────────────────────────┘
                                   │
 ┌─────────────────────────────────┼──────────────────────────────────┐
 │                    kagent-debugger               │                  │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │         RuntimeDebugger — Observability API                 │  │
 │  │  timeline(traceId) → reconstruct execution DAG              │  │
 │  │  query(traceId)    → access TimelineQueryEngine             │  │
 │  │  inspect(nodeId)   → node lookup across traces              │  │
 │  │  children/parents  → graph navigation                       │  │
 │  └───────────────────────────┬──────────────────────────────────┘  │
 │  ┌───────────────────────────┼──────────────────────────────────┐  │
 │  │          TimelineProjection + TimelineQueryEngine           │  │
 │  │                                                             │  │
 │  │  RuntimeEvent → EventStore → TimelineProjection            │  │
 │  │    ↓ TimelineGraph (nodes + edges + indices)               │  │
 │  │    ↓ TimelineQueryEngine (byStep/byTool/criticalPath/... ) │  │
 │  │                                                             │  │
 │  │  Edge types: SEQUENTIAL | CAUSAL | TOOL_FLOW               │  │
 │  └─────────────────────────────────────────────────────────────┘  │
 └─────────────────────────────────┬──────────────────────────────────┘
                                   │ recorded during
 ┌─────────────────────────────────┼──────────────────────────────────┐
 │                    kagent-protocol               │                  │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │     Execution Protocol + KAgentRuntime Facade                │  │
 │  │                                                              │  │
 │  │  execute(AgentRequest) → AgentResponse                       │  │
 │  │  stream(AgentRequest)  → Flow<RuntimeEvent>                  │  │
 │  │  trace(traceId)        → List<RuntimeEvent>                  │  │
 │  │                                                              │  │
 │  │  step() / setState() / getState() — execution DSL            │  │
 │  │  checkpoint / replayToCheckpoint — state management          │  │
 │  │  RuntimeInterceptor — OkHttp-style control plane             │  │
 │  └──────────────────────────────────────────────────────────────┘  │
 └─────────────────────────────────┬──────────────────────────────────┘
                                   │ runs on
 ┌─────────────────────────────────┼──────────────────────────────────┐
 │                    kagent-core  │                                  │
 │  ┌──────────────────────────────────────────────────────────────┐  │
 │  │              Reactive Memory & Fiber Scheduler               │  │
 │  │                                                              │  │
 │  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │  │
 │  │  │    Memory     │  │ Dependency   │  │   Fiber          │   │  │
 │  │  │  Snapshot/Diff│  │ Graph        │  │   Scheduler      │   │  │
 │  │  │  LRU Eviction │  │ Derived State│  │   RuntimeHeart   │   │  │
 │  │  │  State Store  │  │ Invalidation │  │   Task Queue     │   │  │
 │  │  └──────────────┘  └──────────────┘  └──────────────────┘   │  │
 │  └──────────────────────────────────────────────────────────────┘  │
 └─────────────────────────────────────────────────────────────────────┘
```

### Data flow through the stack

```
Agent Code                          (user-defined step{} blocks)
    │
    ▼
KAgentRuntime.execute/stream()      (kagent-protocol)
    │  emit RuntimeEvent subtypes
    ▼
EventStore.record()                 (kagent-protocol)
    │  assign stateVersion
    ▼
TimelineProjection.project()        (kagent-debugger)
    │  → TimelineGraph + TimelineIndices
    ▼
TimelineQueryEngine                 (kagent-debugger)
    │  → criticalPath, byStep, descendants, ...
    ▼
RuntimeDebugger API                 (kagent-debugger)
    │  → timeline(), query(), inspect(), children(), parents()
    ▼
Runtime Inspector (Compose UI)      (kagent-inspector, v0.7)
    │  → timeline DAG visualization, state inspection
    ▼
Developer                           (you)
```

### Module lifecycle alignment

Each module follows a distinct lifecycle cadence, aligned to its risk profile:

| Module | Stability | Iteration cadence | Breaking change risk |
|--------|-----------|------------------|---------------------|
| `kagent-core` | Stable | Slow (weeks) | Very low — kernel must not break |
| `kagent-protocol` | Stable | Moderate (weeks) | Low — API surface needs care |
| `kagent-debugger` | Maturing | Fast (days–weeks) | Moderate — new query capabilities |
| `kagent-inspector` | Experimental | Very fast (daily) | High — UI experiments, Compose API churn |
| `kagent-demo` | Volatile | Per-demo | Highest — demo code is disposable |

