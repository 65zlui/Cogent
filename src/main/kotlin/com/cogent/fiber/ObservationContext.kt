package com.cogent.fiber

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ObservationContext {
    private val currentObservers = ThreadLocal<MutableSet<String>?>()

    fun enterReading(targetId: String) {
        val observers = currentObservers.get() ?: run {
            val set = mutableSetOf<String>()
            currentObservers.set(set)
            set
        }
        observers.add(targetId)
    }

    fun getCurrentObservers(): Set<String> {
        return currentObservers.get().orEmpty().toSet()
    }

    fun exitReading() {
        currentObservers.remove()
    }

    fun <T> observe(block: () -> T): Pair<T, Set<String>> {
        enterReading("")
        val result = block()
        val deps = getCurrentObservers() - setOf("")
        exitReading()
        return result to deps
    }
}

object GlobalObservationContext {
    private val threadLocal = ThreadLocal<MutableSet<String>?>()

    fun startTracking() {
        threadLocal.set(mutableSetOf())
    }

    fun stopTracking(): Set<String> {
        val deps = threadLocal.get().orEmpty().toSet()
        threadLocal.remove()
        return deps
    }

    fun isTracking(): Boolean {
        return threadLocal.get() != null
    }

    fun recordDependency(stateId: String) {
        threadLocal.get()?.add(stateId)
    }
}
