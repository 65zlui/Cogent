package com.cogent.memory.dependency

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DependencyTracker {
    
    private val mutex = Mutex()
    
    private val dependents = mutableMapOf<String, MutableSet<String>>()
    
    private val dependencies = mutableMapOf<String, MutableSet<String>>()
    
    suspend fun addDependency(sourceId: String, targetId: String) {
        mutex.withLock {
            dependents.getOrPut(sourceId) { mutableSetOf() }.add(targetId)
            dependencies.getOrPut(targetId) { mutableSetOf() }.add(sourceId)
        }
    }
    
    suspend fun removeDependency(sourceId: String, targetId: String) {
        mutex.withLock {
            dependents[sourceId]?.remove(targetId)
            dependencies[targetId]?.remove(sourceId)
            
            if (dependents[sourceId].isNullOrEmpty()) {
                dependents.remove(sourceId)
            }
            if (dependencies[targetId].isNullOrEmpty()) {
                dependencies.remove(targetId)
            }
        }
    }
    
    suspend fun getDependents(sourceId: String): Set<String> {
        return mutex.withLock {
            dependents[sourceId].orEmpty().toSet()
        }
    }
    
    suspend fun getDependencies(targetId: String): Set<String> {
        return mutex.withLock {
            dependencies[targetId].orEmpty().toSet()
        }
    }
    
    suspend fun invalidate(sourceId: String): Set<String> {
        val affected = mutableSetOf<String>()
        collectAffected(sourceId, affected)
        return affected
    }
    
    private suspend fun collectAffected(sourceId: String, visited: MutableSet<String>) {
        val directDependents = getDependents(sourceId)
        directDependents.forEach { dependent ->
            if (dependent !in visited) {
                visited.add(dependent)
                collectAffected(dependent, visited)
            }
        }
    }
    
    suspend fun clear() {
        mutex.withLock {
            dependents.clear()
            dependencies.clear()
        }
    }
    
    suspend fun size(): Int {
        return mutex.withLock {
            dependents.size
        }
    }
    
    suspend fun getGraphSnapshot(): Map<String, Set<String>> {
        return mutex.withLock {
            dependents.mapValues { it.value.toSet() }.toMap()
        }
    }
}
