package com.cogent.examples.chat

import kotlin.test.*

class StreamAggregatorTest {

    @Test
    fun `basic lifecycle emits start delta and end`() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { p, m -> events.add("start($p,$m)") },
            onStreamDelta = { a, d -> events.add("delta($d)") },
            onStreamEnd = { l, m -> events.add("end($l,$m)") },
            windowTokens = 100  // large window — rely on end() to flush
        )

        agg.start("test-provider", "test-model")
        agg.accept("Hello")
        agg.accept(" world")
        agg.accept("!")
        agg.end(13, "test-model")

        assertTrue(events.any { it.startsWith("start(") }, "should emit streamStart")
        assertTrue(events.any { it.startsWith("delta(") }, "should emit streamDelta")
        assertTrue(events.any { it.startsWith("end(") }, "should emit streamEnd")

        // All content flushed by end() as a single delta
        assertTrue(events.any { it == "delta(Hello world!)" }, "should flush all accumulated content")
    }

    @Test
    fun `count window triggers flush after threshold`() = kotlinx.coroutines.runBlocking {
        val deltas = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> },
            onStreamDelta = { a, d -> deltas.add("$d") },
            onStreamEnd = { _, _ -> },
            windowTokens = 3
        )

        agg.start()
        // windowBuffer.length >= 3 triggers flush after "ABC" (3 chars)
        agg.accept("AB")
        agg.accept("C")

        assertEquals(1, deltas.size, "should flush after 3 characters accumulated in buffer")
    }

    @Test
    fun `start resets internal state`() = kotlinx.coroutines.runBlocking {
        val deltas = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> },
            onStreamDelta = { _, d -> deltas.add(d) },
            onStreamEnd = { _, _ -> },
            windowTokens = 100  // large window — don't auto-flush
        )

        // First session
        agg.start()
        agg.accept("first")
        agg.end(5, null)

        val firstCount = deltas.size

        // Second session
        agg.start()
        agg.accept("second")
        agg.end(6, null)

        // Both sessions should produce deltas independently
        assertTrue(deltas.size > firstCount, "second session should produce new deltas")
    }

    @Test
    fun `accept without start does nothing`() = kotlinx.coroutines.runBlocking {
        var deltaCalled = false
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> },
            onStreamDelta = { _, _ -> deltaCalled = true },
            onStreamEnd = { _, _ -> },
            windowTokens = 1
        )

        agg.accept("orphan")
        assertFalse(deltaCalled, "accept without start should not emit")
    }

    @Test
    fun `end before accepting emits only start and end`() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> events.add("start") },
            onStreamDelta = { _, _ -> events.add("delta") },
            onStreamEnd = { _, _ -> events.add("end") },
            windowTokens = 5
        )

        agg.start()
        agg.end(0, null)

        assertTrue(events.contains("start"), "should emit start")
        assertTrue(events.contains("end"), "should emit end")
        assertFalse(events.contains("delta"), "should NOT emit delta when empty")
    }

    @Test
    fun `flush emits delta with correct accumulated and delta content`() = kotlinx.coroutines.runBlocking {
        val deltas = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> },
            onStreamDelta = { _, d -> deltas.add(d) },
            onStreamEnd = { _, _ -> },
            windowTokens = 100  // never auto-flush
        )

        agg.start()
        agg.accept("part1")
        agg.accept("part2")
        agg.flush()

        assertEquals(1, deltas.size, "first flush should emit one delta")
        assertEquals("part1part2", deltas[0], "flush should emit correct delta content")

        // Accept more and flush again
        agg.accept("part3")
        agg.flush()

        assertEquals(2, deltas.size, "second flush should emit another delta")
        assertEquals("part3", deltas[1], "second flush should emit remaining delta only")
    }

    @Test
    fun `end with remaining content flushes before emitting end`() = kotlinx.coroutines.runBlocking {
        val deltas = mutableListOf<String>()
        val agg = StreamAggregator(
            onStreamStart = { _, _ -> },
            onStreamDelta = { _, d -> deltas.add(d) },
            onStreamEnd = { _, _ -> },
            windowTokens = 100  // never auto-flush
        )

        agg.start()
        agg.accept("lingering")
        agg.end(9, null)

        assertEquals(1, deltas.size, "end should flush remaining content")
        assertEquals("lingering", deltas[0], "delta should contain all content")
    }
}
