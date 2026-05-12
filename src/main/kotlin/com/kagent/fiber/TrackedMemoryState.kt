package com.kagent.fiber

import com.kagent.memory.dependency.DependencyTracker
import com.kagent.memory.state.ReactiveMemoryState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackedMemoryState<T>(
    val id: String,
    initialValue: T,
    private val tracker: DependencyTracker
) {
    private val mutex = Mutex()
    private val _state = ReactiveMemoryState<T>(id, initialValue)
    private val derivedDependents = mutableMapOf<String, MutableSet<String>>()

    var value: T
        get() {
            if (GlobalObservationContext.isTracking()) {
                GlobalObservationContext.recordDependency(id)
            }
            return _state.value
        }
        set(newValue) {
            _state.value = newValue
        }

    suspend fun getWithTracking(): T {
        if (GlobalObservationContext.isTracking()) {
            GlobalObservationContext.recordDependency(id)
        }
        return _state.value
    }

    suspend fun registerDerivedState(derivedStateId: String) {
        mutex.withLock {
            derivedDependents.getOrPut(id) { mutableSetOf() }.add(derivedStateId)
            tracker.addDependency(id, derivedStateId)
        }
    }

    suspend fun getDerivedDependents(): Set<String> {
        return mutex.withLock {
            derivedDependents[id].orEmpty().toSet()
        }
    }
}
