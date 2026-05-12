package com.kagent.memory.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

open class ReactiveMemoryState<T>(
    override val id: String,
    initialValue: T
) : MutableMemoryState<T> {

    private val _stateFlow = MutableStateFlow(initialValue)
    val stateFlow: StateFlow<T> = _stateFlow.asStateFlow()

    open override var value: T
        get() = _stateFlow.value
        set(newValue) {
            _stateFlow.value = newValue
        }

    fun asFlow(): StateFlow<T> = stateFlow

    override fun toString(): String = "ReactiveMemoryState(id='$id', value=$value)"
}
