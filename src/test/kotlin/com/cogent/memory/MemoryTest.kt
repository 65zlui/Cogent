package com.cogent.memory

import com.cogent.memory.core.Memory
import com.cogent.memory.diff.DefaultDiffEngine
import com.cogent.memory.dsl.memory
import com.cogent.memory.snapshot.DefaultMemorySnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemoryTest {

    @Test
    fun `test setState and getState`() = runTest {
        val memory = Memory()
        
        memory.setState("name", "Tom")
        assertEquals("Tom", memory.getState<String>("name"))
        
        memory.setState("age", 25)
        assertEquals(25, memory.getState<Int>("age"))
    }

    @Test
    fun `test getState returns null for nonexistent key`() = runTest {
        val memory = Memory()
        assertNull(memory.getState<String>("nonexistent"))
    }

    @Test
    fun `test setState overwrites existing value`() = runTest {
        val memory = Memory()
        
        memory.setState("task", "Book Flight")
        assertEquals("Book Flight", memory.getState<String>("task"))
        
        memory.setState("task", "Book Hotel")
        assertEquals("Book Hotel", memory.getState<String>("task"))
    }

    @Test
    fun `test removeState`() = runTest {
        val memory = Memory()
        
        memory.setState("task", "Book Flight")
        assertNotNull(memory.getState<String>("task"))
        
        memory.removeState("task")
        assertNull(memory.getState<String>("task"))
    }

    @Test
    fun `test getAllStateIds`() = runTest {
        val memory = Memory()
        
        memory.setState("a", 1)
        memory.setState("b", 2)
        memory.setState("c", 3)
        
        assertEquals(setOf("a", "b", "c"), memory.getAllStateIds())
    }

    @Test
    fun `test snapshot and restore`() = runTest {
        val memory = Memory()
        
        memory.setState("task", "Book Flight")
        memory.setState("city", "Tokyo")
        
        val snapshot = memory.snapshot()
        assertEquals("Book Flight", snapshot.states["task"])
        assertEquals("Tokyo", snapshot.states["city"])
        
        memory.setState("task", "Book Hotel")
        memory.setState("city", "Osaka")
        
        memory.restore(snapshot)
        
        assertEquals("Book Flight", memory.getState<String>("task"))
        assertEquals("Tokyo", memory.getState<String>("city"))
    }

    @Test
    fun `test diff added_keys`() = runTest {
        val memory = Memory()
        
        val snapshot = memory.snapshot()
        
        memory.setState("newKey", "newValue")
        
        val diff = memory.diff(snapshot)
        
        assertEquals(setOf("newKey"), diff.added.keys)
        assertEquals("newValue", diff.added["newKey"])
    }

    @Test
    fun `test diff_modified_keys`() = runTest {
        val memory = Memory()
        
        memory.setState("city", "Tokyo")
        val snapshot = memory.snapshot()
        
        memory.setState("city", "Osaka")
        
        val diff = memory.diff(snapshot)
        
        assertTrue("city" in diff.modified)
        assertEquals("Tokyo" to "Osaka", diff.modified["city"])
    }

    @Test
    fun `test diff_removed_keys`() = runTest {
        val memory = Memory()
        
        memory.setState("temp", "value")
        val snapshot = memory.snapshot()
        
        memory.removeState("temp")
        
        val diff = memory.diff(snapshot)
        
        assertTrue("temp" in diff.removed)
    }

    @Test
    fun `test LRU eviction`() = runTest {
        val memory = Memory(maxCapacity = 3)
        
        memory.setState("a", 1)
        memory.setState("b", 2)
        memory.setState("c", 3)
        
        assertEquals(setOf("a", "b", "c"), memory.getAllStateIds())
        
        memory.setState("d", 4)
        
        assertEquals(3, memory.size())
        assertFalse("a" in memory.getAllStateIds())
        assertTrue("b" in memory.getAllStateIds())
        assertTrue("c" in memory.getAllStateIds())
        assertTrue("d" in memory.getAllStateIds())
    }

    @Test
    fun `test DSL memory creation`() = runTest {
        val memory = memory {
            id("test-memory")
            maxCapacity(10)
            
            state("userName", "Tom")
            state("task", "Book Flight")
        }
        
        assertEquals("Tom", memory.getState<String>("userName"))
        assertEquals("Book Flight", memory.getState<String>("task"))
    }

    @Test
    fun `test clear`() = runTest {
        val memory = Memory()
        
        memory.setState("a", 1)
        memory.setState("b", 2)
        
        memory.clear()
        
        assertEquals(0, memory.size())
        assertTrue(memory.getAllStateIds().isEmpty())
    }
}
