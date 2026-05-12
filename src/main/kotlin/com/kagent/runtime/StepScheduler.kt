package com.kagent.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

data class StepInfo(
    val name: String,
    val status: StepStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

class StepScheduler {
    private val mutex = Mutex()
    private val steps = mutableListOf<StepInfo>()
    private var currentStepIndex = 0
    
    suspend fun addStep(name: String, metadata: Map<String, Any?> = emptyMap()): StepInfo {
        return mutex.withLock {
            val step = StepInfo(
                name = name,
                status = StepStatus.PENDING,
                startTime = System.currentTimeMillis(),
                metadata = metadata
            )
            steps.add(step)
            step
        }
    }
    
    suspend fun startStep(name: String) {
        mutex.withLock {
            val index = steps.indexOfLast { it.name == name && it.status == StepStatus.PENDING }
            if (index != -1) {
                steps[index] = steps[index].copy(
                    status = StepStatus.RUNNING,
                    startTime = System.currentTimeMillis()
                )
                currentStepIndex = index
            }
        }
    }
    
    suspend fun completeStep(name: String) {
        mutex.withLock {
            val index = steps.indexOfLast { it.name == name && it.status == StepStatus.RUNNING }
            if (index != -1) {
                steps[index] = steps[index].copy(
                    status = StepStatus.COMPLETED,
                    endTime = System.currentTimeMillis()
                )
                currentStepIndex++
            }
        }
    }
    
    suspend fun failStep(name: String, error: String? = null) {
        mutex.withLock {
            val index = steps.indexOfLast { it.name == name && it.status == StepStatus.RUNNING }
            if (index != -1) {
                val metadata = if (error != null) {
                    steps[index].metadata + ("error" to error)
                } else {
                    steps[index].metadata
                }
                steps[index] = steps[index].copy(
                    status = StepStatus.FAILED,
                    endTime = System.currentTimeMillis(),
                    metadata = metadata
                )
            }
        }
    }
    
    suspend fun getCurrentStep(): StepInfo? {
        return mutex.withLock {
            steps.lastOrNull { it.status == StepStatus.RUNNING }
        }
    }
    
    suspend fun getSteps(): List<StepInfo> {
        return mutex.withLock {
            steps.toList()
        }
    }
    
    suspend fun getPendingSteps(): List<StepInfo> {
        return mutex.withLock {
            steps.filter { it.status == StepStatus.PENDING }
        }
    }
    
    suspend fun getCompletedSteps(): List<StepInfo> {
        return mutex.withLock {
            steps.filter { it.status == StepStatus.COMPLETED }
        }
    }
    
    suspend fun reset() {
        mutex.withLock {
            steps.clear()
            currentStepIndex = 0
        }
    }
    
    suspend fun size(): Int {
        return mutex.withLock {
            steps.size
        }
    }
}
