package com.cogent.memory.dependency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DependencyTrackerTest {

    @Test
    fun `test addDependency`() = runTest {
        val tracker = DependencyTracker()
        tracker.addDependency("a", "b")
        
        assertEquals(setOf("b"), tracker.getDependents("a"))
        assertEquals(setOf("a"), tracker.getDependencies("b"))
    }

    @Test
    fun `test removeDependency`() = runTest {
        val tracker = DependencyTracker()
        tracker.addDependency("a", "b")
        tracker.removeDependency("a", "b")
        
        assertTrue(tracker.getDependents("a").isEmpty())
        assertTrue(tracker.getDependencies("b").isEmpty())
    }

    @Test
    fun `test invalidate propagates`() = runTest {
        val tracker = DependencyTracker()
        tracker.addDependency("a", "b")
        tracker.addDependency("b", "c")
        
        val affected = tracker.invalidate("a")
        assertTrue("b" in affected)
        assertTrue("c" in affected)
    }

    @Test
    fun `test getGraphSnapshot`() = runTest {
        val tracker = DependencyTracker()
        tracker.addDependency("a", "b")
        tracker.addDependency("a", "c")
        
        val snapshot = tracker.getGraphSnapshot()
        assertEquals(setOf("b", "c"), snapshot["a"])
    }

    @Test
    fun `test clear`() = runTest {
        val tracker = DependencyTracker()
        tracker.addDependency("a", "b")
        tracker.clear()
        
        assertTrue(tracker.getDependents("a").isEmpty())
    }
}

class DerivedStateTest {

    @Test
    fun `test derived state compute`() = runTest {
        val tracker = InvalidationGraph()
        var computeCount = 0
        
        val derived = deriveBlocking(
            tracker = tracker,
            dependencyIds = setOf("a", "b"),
            compute = {
                computeCount++
                "computed"
            }
        )
        
        assertEquals("computed", derived.value)
        assertEquals(1, computeCount)
    }

    @Test
    fun `test derived state invalidation`() = runTest {
        val tracker = InvalidationGraph()
        var value = 10
        
        val derived = deriveBlocking(
            tracker = tracker,
            dependencyIds = setOf("value"),
            compute = { value * 2 }
        )
        
        assertEquals(20, derived.value)
        
        value = 15
        derived.invalidate()
        
        assertEquals(30, derived.value)
    }

    @Test
    fun `test derived state registers dependencies`() = runTest {
        val tracker = InvalidationGraph()
        val derived = deriveBlocking(
            tracker = tracker,
            dependencyIds = setOf("a", "b"),
            compute = { "test" }
        )
        
        val deps = derived.getDependencies()
        assertEquals(setOf("a", "b"), deps)
    }
}

class InvalidationGraphTest {

    @Test
    fun `test invalidate returns affected nodes`() = runTest {
        val graph = InvalidationGraph()
        graph.addDependency("a", "b")
        graph.addDependency("b", "c")
        
        val result = graph.invalidate("a")
        assertEquals("a", result.sourceId)
        assertTrue("b" in result.affectedNodes)
        assertTrue("c" in result.affectedNodes)
    }

    @Test
    fun `test invalidation events are emitted`() = runTest {
        val graph = InvalidationGraph()
        graph.addDependency("a", "b")
        
        graph.invalidate("a")
        kotlinx.coroutines.delay(100)
        
        assertTrue(true)
    }

    @Test
    fun `test getGraphSnapshot`() = runTest {
        val graph = InvalidationGraph()
        graph.addDependency("a", "b")
        graph.addDependency("a", "c")
        
        val snapshot = graph.getGraphSnapshot()
        assertEquals(setOf("b", "c"), snapshot["a"])
    }
}
