package com.kagent.trace

import com.kagent.memory.snapshot.MemorySnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TraceEvent(
    val type: String,
    val timestamp: Long,
    val metadata: Map<String, Any?> = emptyMap()
)

data class TraceCheckpoint(
    val name: String,
    val timestamp: Long,
    val snapshot: MemorySnapshot
)

data class TraceInvalidation(
    val sourceId: String,
    val affectedNodes: Set<String>,
    val timestamp: Long
)

class ExecutionTrace {
    private val mutex = Mutex()
    private val events = mutableListOf<TraceEvent>()
    private val checkpoints = mutableListOf<TraceCheckpoint>()
    private val invalidations = mutableListOf<TraceInvalidation>()
    
    suspend fun recordEvent(type: String, metadata: Map<String, Any?> = emptyMap()) {
        mutex.withLock {
            events.add(TraceEvent(
                type = type,
                timestamp = System.currentTimeMillis(),
                metadata = metadata
            ))
        }
    }
    
    suspend fun recordCheckpoint(name: String, snapshot: MemorySnapshot) {
        mutex.withLock {
            checkpoints.add(TraceCheckpoint(
                name = name,
                timestamp = System.currentTimeMillis(),
                snapshot = snapshot
            ))
        }
    }
    
    suspend fun recordInvalidation(sourceId: String, affectedNodes: Set<String>) {
        mutex.withLock {
            invalidations.add(TraceInvalidation(
                sourceId = sourceId,
                affectedNodes = affectedNodes,
                timestamp = System.currentTimeMillis()
            ))
        }
    }
    
    suspend fun getEvents(): List<TraceEvent> {
        return mutex.withLock { events.toList() }
    }
    
    suspend fun getCheckpoints(): List<TraceCheckpoint> {
        return mutex.withLock { checkpoints.toList() }
    }
    
    suspend fun getInvalidations(): List<TraceInvalidation> {
        return mutex.withLock { invalidations.toList() }
    }
    
    suspend fun getEventCount(): Int {
        return mutex.withLock { events.size }
    }
    
    suspend fun getCheckpointCount(): Int {
        return mutex.withLock { checkpoints.size }
    }
    
    suspend fun getEventsByType(type: String): List<TraceEvent> {
        return mutex.withLock { events.filter { it.type == type } }
    }
    
    suspend fun getTimeline(): List<TimelineEntry> {
        return mutex.withLock {
            val allEntries = mutableListOf<TimelineEntry>()
            
            events.forEach { event ->
                allEntries.add(TimelineEntry.Event(event))
            }
            
            checkpoints.forEach { checkpoint ->
                allEntries.add(TimelineEntry.Checkpoint(checkpoint))
            }
            
            invalidations.forEach { invalidation ->
                allEntries.add(TimelineEntry.Invalidation(invalidation))
            }
            
            allEntries.sortedBy { it.timestamp }
        }
    }
    
    suspend fun clear() {
        mutex.withLock {
            events.clear()
            checkpoints.clear()
            invalidations.clear()
        }
    }
    
    override fun toString(): String {
        return "ExecutionTrace(events=${events.size}, checkpoints=${checkpoints.size}, invalidations=${invalidations.size})"
    }
}

sealed class TimelineEntry {
    abstract val timestamp: Long
    
    data class Event(val event: TraceEvent) : TimelineEntry() {
        override val timestamp: Long get() = event.timestamp
    }
    
    data class Checkpoint(val checkpoint: TraceCheckpoint) : TimelineEntry() {
        override val timestamp: Long get() = checkpoint.timestamp
    }
    
    data class Invalidation(val invalidation: TraceInvalidation) : TimelineEntry() {
        override val timestamp: Long get() = invalidation.timestamp
    }
}
