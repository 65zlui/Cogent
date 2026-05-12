package com.kagent.memory.dependency

import com.kagent.memory.state.ReactiveMemoryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DerivedState<T>(
    private val compute: () -> T,
    private val tracker: DependencyTracker,
    private val dependencyIds: Set<String>
) {
    private val mutex = Mutex()
    private val _stateFlow = MutableStateFlow(compute())
    val stateFlow: StateFlow<T> = _stateFlow.asStateFlow()
    
    val value: T
        get() = _stateFlow.value
    
    suspend fun invalidate() {
        mutex.withLock {
            val newValue = compute()
            _stateFlow.value = newValue
        }
    }
    
    suspend fun getDependencies(): Set<String> = dependencyIds.toSet()
    
    suspend fun registerDependencies() {
        dependencyIds.forEach { depId ->
            tracker.addDependency(depId, "derived:${System.identityHashCode(this)}")
        }
    }
    
    override fun toString(): String = "DerivedState(value=$value, dependencies=$dependencyIds)"
}

fun <T> derive(
    tracker: InvalidationGraph,
    dependencyIds: Set<String>,
    compute: () -> T
): DerivedState<T> {
    val derived = DerivedState(compute, tracker.tracker, dependencyIds)
    return derived
}

suspend fun <T> deriveBlocking(
    tracker: InvalidationGraph,
    dependencyIds: Set<String>,
    compute: () -> T
): DerivedState<T> {
    val derived = DerivedState(compute, tracker.tracker, dependencyIds)
    derived.registerDependencies()
    return derived
}
