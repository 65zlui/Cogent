package com.cogent.memory.snapshot

interface MemorySnapshot {
    val timestamp: Long
    val states: Map<String, Any?>
}
