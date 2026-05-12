package com.cogent.memory.core

import com.cogent.memory.diff.ContextPatch
import com.cogent.memory.diff.DefaultDiffEngine
import com.cogent.memory.diff.MemoryDiffEngine
import com.cogent.memory.eviction.LRUEvictionPolicy
import com.cogent.memory.snapshot.DefaultMemorySnapshot
import com.cogent.memory.snapshot.MemorySnapshot
import com.cogent.memory.state.ReactiveMemoryState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Memory(
    val id: String = "default",
    private val maxCapacity: Int = 100,
    private val diffEngine: MemoryDiffEngine = DefaultDiffEngine(),
    private val evictionPolicy: LRUEvictionPolicy = LRUEvictionPolicy(maxCapacity)
) {
    private val mutex = Mutex()

    private val states = mutableMapOf<String, ReactiveMemoryState<Any?>>()

    private val _stateChangeEvents = MutableSharedFlow<Pair<String, Any?>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stateChangeEvents: Flow<Pair<String, Any?>> = _stateChangeEvents

    private val accessOrder = mutableListOf<String>()

    suspend fun setState(id: String, value: Any?): Any? {
        return mutex.withLock {
            val newState = ReactiveMemoryState<Any?>(id, value)
            states[id] = newState
            evictionPolicy.recordAccess(id)
            evictIfNecessary()
            _stateChangeEvents.tryEmit(id to value)
            value
        }
    }

    suspend fun <T> getState(id: String): T? {
        return mutex.withLock {
            @Suppress("UNCHECKED_CAST")
            val state = states[id] as? ReactiveMemoryState<T>
            if (state != null) {
                evictionPolicy.recordAccess(id)
            }
            state?.value
        }
    }

    suspend fun <T> getStateFlow(id: String): kotlinx.coroutines.flow.StateFlow<T>? {
        @Suppress("UNCHECKED_CAST")
        val state = states[id] as? ReactiveMemoryState<T> ?: return null
        evictionPolicy.recordAccess(id)
        return state.asFlow()
    }

    fun <T> getStateBlocking(id: String): T? {
        @Suppress("UNCHECKED_CAST")
        val state = states[id] as? ReactiveMemoryState<T>
        return state?.value
    }

    fun <T> getStateFlowBlocking(id: String): kotlinx.coroutines.flow.StateFlow<T>? {
        @Suppress("UNCHECKED_CAST")
        val state = states[id] as? ReactiveMemoryState<T>
        return state?.asFlow()
    }

    suspend fun removeState(id: String) {
        mutex.withLock {
            states.remove(id)
            evictionPolicy.remove(id)
        }
    }

    suspend fun snapshot(): MemorySnapshot {
        return mutex.withLock {
            DefaultMemorySnapshot(
                timestamp = System.currentTimeMillis(),
                states = states.mapValues { it.value.value }
            )
        }
    }

    suspend fun restore(snapshot: MemorySnapshot) {
        mutex.withLock {
            states.clear()
            evictionPolicy.clear()
            snapshot.states.forEach { (key, value) ->
                states[key] = ReactiveMemoryState(key, value)
                evictionPolicy.recordAccess(key)
            }
        }
    }

    suspend fun diff(oldSnapshot: MemorySnapshot): ContextPatch {
        return mutex.withLock {
            val currentStates = states.mapValues { it.value.value }
            diffEngine.diff(oldSnapshot.states, currentStates)
        }
    }

    fun getAllStateIds(): Set<String> = states.keys.toSet()

    fun getAllStates(): Map<String, Any?> = states.mapValues { it.value.value }

    fun size(): Int = states.size

    suspend fun clear() {
        mutex.withLock {
            states.clear()
            evictionPolicy.clear()
        }
    }

    private fun evictIfNecessary() {
        while (states.size > maxCapacity) {
            val toRemove = evictionPolicy.getLeastRecentlyUsed()
            toRemove?.let {
                states.remove(it)
                evictionPolicy.remove(it)
            } ?: break
        }
    }

    override fun toString(): String {
        return "Memory(id='$id', size=${states.size}, states=${states.keys})"
    }
}
