package com.kagent.fiber

import com.kagent.memory.dependency.DependencyTracker
import com.kagent.memory.dependency.InvalidationGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SuspendableDerivedState<T>(
    private val id: String,
    private val compute: suspend () -> T,
    private val tracker: DependencyTracker,
    initialDependencyIds: Set<String> = emptySet(),
    initialValue: T? = null
) {
    private val mutex = Mutex()
    private val _stateFlow = MutableStateFlow<T?>(initialValue)
    val stateFlow: StateFlow<T?> = _stateFlow.asStateFlow()

    private var _dependencies = initialDependencyIds.toMutableSet()

    val value: T?
        get() = _stateFlow.value

    suspend fun computeValue(): T {
        return mutex.withLock {
            val result = compute()
            _stateFlow.value = result
            result
        }
    }

    suspend fun invalidate() {
        mutex.withLock {
            val newValue = compute()
            _stateFlow.value = newValue
        }
    }

    suspend fun updateDependencies(newDeps: Set<String>) {
        mutex.withLock {
            val removed = _dependencies - newDeps
            val added = newDeps - _dependencies

            removed.forEach { dep ->
                tracker.removeDependency(dep, id)
            }
            added.forEach { dep ->
                tracker.addDependency(dep, id)
            }

            _dependencies = newDeps.toMutableSet()
        }
    }

    suspend fun getDependencies(): Set<String> {
        return mutex.withLock {
            _dependencies.toSet()
        }
    }

    fun getId(): String = id

    override fun toString(): String = "SuspendableDerivedState(id='$id', value=$value, deps=$_dependencies)"
}

suspend fun <T> derivedSuspend(
    id: String,
    tracker: DependencyTracker,
    compute: suspend () -> T
): SuspendableDerivedState<T> {
    GlobalObservationContext.startTracking()
    val result: T
    val deps: Set<String>
    try {
        result = compute()
        deps = GlobalObservationContext.stopTracking()
    } catch (e: Exception) {
        GlobalObservationContext.stopTracking()
        throw e
    }

    val derived = SuspendableDerivedState(
        id = id,
        compute = compute,
        tracker = tracker,
        initialDependencyIds = deps,
        initialValue = result
    )

    deps.forEach { dep ->
        tracker.addDependency(dep, id)
    }

    return derived
}

suspend fun <T> derivedSuspendWithDeps(
    id: String,
    tracker: DependencyTracker,
    dependencyIds: Set<String>,
    compute: suspend () -> T
): SuspendableDerivedState<T> {
    GlobalObservationContext.startTracking()
    val result: T
    val trackedDeps: Set<String>
    try {
        result = compute()
        trackedDeps = GlobalObservationContext.stopTracking()
    } catch (e: Exception) {
        GlobalObservationContext.stopTracking()
        throw e
    }

    val allDeps = dependencyIds + trackedDeps

    val derived = SuspendableDerivedState(
        id = id,
        compute = compute,
        tracker = tracker,
        initialDependencyIds = allDeps,
        initialValue = result
    )

    allDeps.forEach { dep ->
        tracker.addDependency(dep, id)
    }

    return derived
}
