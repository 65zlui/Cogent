package com.kagent.memory.eviction

class LRUEvictionPolicy(private val maxCapacity: Int) {
    private val accessOrder = mutableListOf<String>()

    fun recordAccess(id: String) {
        accessOrder.remove(id)
        accessOrder.add(id)
    }

    fun remove(id: String) {
        accessOrder.remove(id)
    }

    fun getLeastRecentlyUsed(): String? {
        return accessOrder.firstOrNull()
    }

    fun clear() {
        accessOrder.clear()
    }

    fun size(): Int = accessOrder.size

    fun getAccessOrder(): List<String> = accessOrder.toList()
}
