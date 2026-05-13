package com.cogent.memory.state

interface MemoryState<T> {
    val id: String
    val value: T
}

interface MutableMemoryState<T> : MemoryState<T> {
    override var value: T
}
