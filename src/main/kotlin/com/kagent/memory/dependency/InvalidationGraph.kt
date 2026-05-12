package com.kagent.memory.dependency

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InvalidationGraph {
    
    private val mutex = Mutex()
    
    internal val tracker = DependencyTracker()
    
    private val invalidationQueue = mutableListOf<String>()
    
    private val _invalidationEvents = MutableSharedFlow<InvalidationEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val invalidationEvents: Flow<InvalidationEvent> = _invalidationEvents
    
    suspend fun addDependency(sourceId: String, targetId: String) {
        tracker.addDependency(sourceId, targetId)
    }
    
    suspend fun remove(sourceId: String) {
        tracker.getDependents(sourceId).forEach { dependent ->
            tracker.removeDependency(sourceId, dependent)
        }
    }
    
    suspend fun invalidate(sourceId: String): InvalidationResult {
        val affected = tracker.invalidate(sourceId)
        
        val event = InvalidationEvent(
            sourceId = sourceId,
            affectedNodes = affected,
            timestamp = System.currentTimeMillis()
        )
        
        _invalidationEvents.tryEmit(event)
        
        return InvalidationResult(
            sourceId = sourceId,
            affectedNodes = affected
        )
    }
    
    suspend fun getDependents(sourceId: String): Set<String> {
        return tracker.getDependents(sourceId)
    }
    
    suspend fun getDependencies(targetId: String): Set<String> {
        return tracker.getDependencies(targetId)
    }
    
    suspend fun getGraphSnapshot(): Map<String, Set<String>> {
        return tracker.getGraphSnapshot()
    }
    
    suspend fun clear() {
        tracker.clear()
    }
}

data class InvalidationEvent(
    val sourceId: String,
    val affectedNodes: Set<String>,
    val timestamp: Long
)

data class InvalidationResult(
    val sourceId: String,
    val affectedNodes: Set<String>
)
