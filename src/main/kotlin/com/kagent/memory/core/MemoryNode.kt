package com.kagent.memory.core

interface MemoryNode {
    val id: String
    val children: List<MemoryNode>
    val lifecycle: MemoryLifecycle
}

enum class MemoryLifecycle {
    EPHEMERAL,
    SESSION,
    PERSISTENT,
    GLOBAL
}
