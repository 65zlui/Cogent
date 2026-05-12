package com.kagent.memory.snapshot

interface MemorySnapshot {
    val timestamp: Long
    val states: Map<String, Any?>
}
