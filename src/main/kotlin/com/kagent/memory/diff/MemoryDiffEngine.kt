package com.kagent.memory.diff

interface ContextPatch {
    val added: Map<String, Any?>
    val removed: Set<String>
    val modified: Map<String, Pair<Any?, Any?>>
    
    fun isEmpty(): Boolean = added.isEmpty() && removed.isEmpty() && modified.isEmpty()
    
    companion object {
        fun empty() = object : ContextPatch {
            override val added = emptyMap<String, Any?>()
            override val removed = emptySet<String>()
            override val modified = emptyMap<String, Pair<Any?, Any?>>()
        }
    }
}

interface MemoryDiffEngine {
    fun diff(
        oldStates: Map<String, Any?>,
        newStates: Map<String, Any?>
    ): ContextPatch
}
