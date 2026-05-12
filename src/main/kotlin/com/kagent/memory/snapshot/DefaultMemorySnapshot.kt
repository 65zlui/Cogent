package com.kagent.memory.snapshot

data class DefaultMemorySnapshot(
    override val timestamp: Long,
    override val states: Map<String, Any?>
) : MemorySnapshot
