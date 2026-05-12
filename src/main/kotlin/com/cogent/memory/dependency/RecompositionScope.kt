package com.cogent.memory.dependency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecompositionScope(
    private val scope: CoroutineScope,
    private val invalidationGraph: InvalidationGraph,
    private val recomposeHandler: suspend (InvalidationResult) -> Unit
) {
    private val mutex = Mutex()
    private var isActive = true
    
    init {
        scope.launch {
            invalidationGraph.invalidationEvents.collect { event ->
                if (isActive) {
                    val result = InvalidationResult(
                        sourceId = event.sourceId,
                        affectedNodes = event.affectedNodes
                    )
                    recomposeHandler(result)
                }
            }
        }
    }
    
    suspend fun invalidate(sourceId: String): InvalidationResult {
        return invalidationGraph.invalidate(sourceId)
    }
    
    suspend fun addDependency(sourceId: String, targetId: String) {
        invalidationGraph.addDependency(sourceId, targetId)
    }
    
    fun cancel() {
        isActive = false
    }
}
