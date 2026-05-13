package com.cogent.runtime

// ============================================================
// EventStore — Bounded, thread-safe event log with versioning
// ============================================================

/**
 * Stored event entry with assigned [stateVersion].
 * [stateVersion] is a monotonic counter assigned by [EventStore.record],
 * serving as a lightweight reference into the execution timeline.
 */
data class EventStoreEntry(
    val traceId: String,
    val event: RuntimeEvent,
    val timestamp: Long = System.currentTimeMillis(),
    val stateVersion: Long = 0
)

/**
 * Thread-safe, bounded event store.
 *
 * Maintains an ordered event log with monotonic [stateVersion] assignment.
 * The version counter is global to the runtime — every recorded event gets
 * an incrementing version number, enabling timeline reconstruction and
 * state reference without copying full snapshots.
 *
 * @param maxEvents Maximum number of events to retain (oldest dropped first).
 */
class EventStore(private val maxEvents: Int = 10_000) {

    private val entries = mutableListOf<EventStoreEntry>()
    private var versionCounter = 0L

    /**
     * Record an event and assign a monotonic stateVersion.
     * @return the assigned [EventStoreEntry] with populated stateVersion.
     */
    fun record(traceId: String, event: RuntimeEvent): EventStoreEntry {
        val version = synchronized(this) { ++versionCounter }
        val entry = EventStoreEntry(traceId, event, System.currentTimeMillis(), version)
        synchronized(entries) {
            entries.add(entry)
            while (entries.size > maxEvents) entries.removeAt(0)
        }
        return entry
    }

    /**
     * Retrieve events for a specific traceId.
     */
    fun getEvents(traceId: String, maxResults: Int = 1000): List<EventStoreEntry> {
        synchronized(entries) {
            return entries
                .filter { it.traceId == traceId }
                .takeLast(maxResults)
                .toList()
        }
    }

    /**
     * Return all stored events (across all traces).
     */
    fun getAllEvents(): List<EventStoreEntry> {
        synchronized(entries) {
            return entries.toList()
        }
    }

    /**
     * Current number of stored events.
     */
    fun size(): Int = synchronized(entries) { entries.size }

    /**
     * Clear all events.
     */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
