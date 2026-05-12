package com.kagent.memory

import com.kagent.runtime.*

fun main() {
    println("=== KAgent Runtime v0.4 - Unified API ===\n")

    // 1. Create runtime with simple API
    println("1. Create Runtime...")
    val runtime = kAgentRuntime(id = "demo", maxConcurrency = 4) {
        step("initialize", priority = 100) {
            setState("status", "initializing")
            setState("user", "alice")
            checkpoint("afterInit")
        }

        step("compute", priority = 50) {
            setState("a", 10)
            setState("b", 20)

            registerDerived("sum", setOf("a", "b")) {
                val a = getStateWithTracking("a") as? Int ?: 0
                val b = getStateWithTracking("b") as? Int ?: 0
                a + b
            }

            setState("status", "computed")
        }

        step("finalize", priority = 10) {
            setState("status", "completed")
            checkpoint("afterFinal")
        }
    }

    println("   Runtime state: ${runtime.state}")
    println("   Memory status: ${runtime.getState<String>("status")}")
    println("   Sum: ${runtime.getState<Int>("derived:sum")}\n")

    // 2. Take snapshot
    println("2. Snapshot...")
    val snapshot = runtime.snapshot()
    println("   Snapshot id: ${snapshot.id}")
    println("   Checkpoints: ${snapshot.checkpointNames}")
    println("   All states: ${snapshot.states.keys}\n")

    // 3. View state changes
    println("3. State Changes Timeline...")
    val changes = runtime.stateChanges()
    changes.forEach { change ->
        when (change.type) {
            StateChangeType.VALUE_SET -> println("   [State] ${change.key}: ${change.oldValue} → ${change.newValue}")
            StateChangeType.STEP_COMPLETE -> println("   [Step Complete] ${change.key}")
            StateChangeType.DERIVED_RECOMPUTE -> println("   [Derived] ${change.key} = ${change.newValue}")
            StateChangeType.CHECKPOINT -> println("   [Checkpoint] ${change.metadata["name"]}")
            else -> println("   [${change.type}] ${change.key}")
        }
    }
    println()

    // 4. Replay to checkpoint
    println("4. Replay to Checkpoint...")
    println("   Current status: ${runtime.getState<String>("status")}")
    runtime.replayToCheckpoint("afterInit")
    println("   After replay: ${runtime.getState<String>("status")}\n")

    // 5. Runtime is the only entry point
    println("5. API Surface...")
    println("   runtime.run()        ✓")
    println("   runtime.state        ✓")
    println("   runtime.events       ✓")
    println("   runtime.snapshot()   ✓")
    println("   runtime.stateChanges() ✓")
    println("   runtime.getState()   ✓")
    println("   runtime.replayToCheckpoint() ✓\n")

    println("=== v0.4 Runtime API Complete ===")
    println("\n🔑 Only 1 class exposed: KAgentRuntime")
    println("🔑 Internal modules hidden: Scheduler, Fiber, Dependency, Derived")
}
