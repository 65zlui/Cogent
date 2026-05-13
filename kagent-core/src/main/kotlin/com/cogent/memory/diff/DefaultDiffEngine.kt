package com.cogent.memory.diff

import com.cogent.memory.diff.ContextPatch

data class DefaultContextPatch(
    override val added: Map<String, Any?>,
    override val removed: Set<String>,
    override val modified: Map<String, Pair<Any?, Any?>>
) : ContextPatch

class DefaultDiffEngine : MemoryDiffEngine {
    override fun diff(
        oldStates: Map<String, Any?>,
        newStates: Map<String, Any?>
    ): ContextPatch {
        val added = newStates.filterKeys { it !in oldStates }
        val removed = oldStates.keys.filter { it !in newStates }.toSet()
        val modified = newStates.filter { (key, value) ->
            key in oldStates && value != oldStates[key]
        }.mapValues { (key, newValue) ->
            oldStates[key] to newValue
        }

        return DefaultContextPatch(
            added = added,
            removed = removed,
            modified = modified
        )
    }
}
