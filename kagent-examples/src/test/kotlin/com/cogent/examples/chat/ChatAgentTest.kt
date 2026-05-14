package com.cogent.examples.chat

import com.cogent.runtime.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Smoke test for the debuggable chat agent.
 * Verifies that the agent produces valid execution traces.
 */
class ChatAgentTest {

    @Test
    fun `chat produces response and timeline graph`() {
        runBlocking {
            val agent = ChatAgent(id = "test-agent")
            val result = agent.chat("hello")

            assertNotNull(result.output, "response should not be null")
            assertFalse(result.output.isEmpty(), "response should not be empty")
            assertNotNull(result.traceId, "traceId should not be null")
            assertTrue(result.durationMs >= 0, "duration should be non-negative")

            val graph = result.graph
            assertNotNull(graph, "timeline graph should not be null")
            assertTrue(graph.nodeCount >= 4, "should have at least RunStart/End + StepStart/End nodes")
            assertTrue(graph.edges.isNotEmpty(), "should have edges")
            assertNotNull(graph.indices, "should have indices")
        }
    }

    @Test
    fun `chat with search intent triggers tool execution`() = runBlocking {
        val agent = ChatAgent(
            id = "search-agent",
            tools = mapOf("search" to SearchTool())
        )
        val result = agent.chat("search kotlin")

        assertNotNull(result.graph, "timeline graph should not be null")
        val graph = result.graph

        val hasToolResultState = graph.nodes.any {
            it.event is RuntimeEvent.MemoryChange &&
                (it.event as RuntimeEvent.MemoryChange).key == "tool_result"
        }
        assertTrue(hasToolResultState, "should have MemoryChange(tool_result) from tool execution")
    }

    @Test
    fun `tool execution emits ToolCall and ToolResult RuntimeEvents`() = runBlocking {
        val agent = ChatAgent(
            id = "tool-event-test",
            tools = mapOf("search" to SearchTool())
        )
        val result = agent.chat("search kotlin")
        val graph = result.graph ?: fail("graph should exist")

        val toolCalls = graph.nodes.filter { it.event is RuntimeEvent.ToolCall }
        val toolResults = graph.nodes.filter { it.event is RuntimeEvent.ToolResult }

        assertTrue(toolCalls.isNotEmpty(), "should have ToolCall events")
        assertTrue(toolResults.isNotEmpty(), "should have ToolResult events")

        // Verify ToolCall has correct tool name
        val toolCall = toolCalls.first().event as RuntimeEvent.ToolCall
        assertEquals("search", toolCall.tool, "ToolCall tool should be 'search'")
        assertTrue(toolCall.input.contains("kotlin"), "ToolCall input should contain query")
    }

    @Test
    fun `tool execution produces TOOL_FLOW edge in timeline`() = runBlocking {
        val agent = ChatAgent(
            id = "tool-flow-test",
            tools = mapOf("calculator" to CalculatorTool())
        )
        val result = agent.chat("calc 2+2")
        val graph = result.graph ?: fail("graph should exist")

        val toolEdges = graph.edges.filter { it.type == EdgeType.TOOL_FLOW }
        assertTrue(toolEdges.isNotEmpty(), "should have TOOL_FLOW edges connecting ToolCall -> ToolResult")
    }

    @Test
    fun `calculator handles multi-operator expressions with precedence`() = runBlocking {
        val tool = CalculatorTool()

        // Basic arithmetic
        assertEquals("2+3*4 = 14", tool.execute("2+3*4"))
        assertEquals("10-2*3 = 4", tool.execute("10-2*3"))

        // Parentheses
        assertEquals("(2+3)*4 = 20", tool.execute("(2+3)*4"))
        assertEquals("(1+2)*(3+4) = 21", tool.execute("(1+2)*(3+4)"))

        // Division and subtraction
        assertEquals("10/2+3 = 8", tool.execute("10/2+3"))
        assertEquals("10-5-2 = 3", tool.execute("10-5-2"))

        // Unary minus
        assertEquals("-5+3 = -2", tool.execute("-5+3"))

        // Edge cases
        assertTrue(tool.execute("1/0").contains("division by zero"),
            "division by zero should be handled")
        assertTrue(tool.execute("").contains("provide"),
            "empty expression should prompt user")
    }

    @Test
    fun `debugger sees traces after multiple chats`() {
        runBlocking {
            val agent = ChatAgent(id = "multi-chat")
            val dbg = agent.debugger()

            agent.chat("first message", traceId = "chat-1")
            agent.chat("second message", traceId = "chat-2")

            val traceIds = dbg.traceIds()
            assertTrue(traceIds.contains("chat-1"), "should contain first trace")
            assertTrue(traceIds.contains("chat-2"), "should contain second trace")
            assertTrue(dbg.eventCount() >= 4, "should have events across both traces")

            val engine1 = dbg.query("chat-1")
            assertNotNull(engine1, "query engine should exist for chat-1")
            assertEquals(2, engine1.byStep("compose").size, "compose step should have 2 nodes (start+end)")

            val engine2 = dbg.query("chat-2")
            assertNotNull(engine2, "query engine should exist for chat-2")
        }
    }

    @Test
    fun `timeline has correct edge types`() = runBlocking {
        val agent = ChatAgent(
            id = "edge-test",
            tools = mapOf("calculator" to CalculatorTool())
        )
        val result = agent.chat("calc 2+2")

        val graph = result.graph
        assertNotNull(graph, "timeline graph should not be null")

        val edgeTypes = graph.edges.map { it.type }.toSet()
        assertTrue(edgeTypes.contains(EdgeType.SEQUENTIAL),
            "should have SEQUENTIAL edges (chronological ordering)")
        assertTrue(edgeTypes.contains(EdgeType.CAUSAL),
            "should have CAUSAL edges (RunStart↔RunEnd, StepStart↔StepEnd)")
        assertTrue(edgeTypes.contains(EdgeType.TOOL_FLOW),
            "should have TOOL_FLOW edges (ToolCall↔ToolResult)")

        val causalEdges = graph.edges.filter { it.type == EdgeType.CAUSAL }
        assertTrue(causalEdges.size >= 5,
            "should have at least 5 CAUSAL edges (4 steps + Run)")

        val seqEdges = graph.edges.filter { it.type == EdgeType.SEQUENTIAL }
        assertEquals(graph.nodeCount - 1, seqEdges.size,
            "SEQUENTIAL edges should link all nodes in order")
    }

    @Test
    fun `critical path analysis works on chat trace`() = runBlocking {
        val agent = ChatAgent(id = "critical-test")
        val result = agent.chat("hello")

        assertNotNull(result.graph, "timeline graph should exist")
        val graph = result.graph
        val engine = TimelineQueryEngine(graph)
        val critical = engine.criticalPath()

        assertTrue(critical.isNotEmpty(), "critical path should not be empty")
        assertEquals(graph.nodes.first().id, critical.first().id,
            "critical path should start at root")
        assertEquals(graph.nodes.last().id, critical.last().id,
            "critical path should end at last node")
    }

    @Test
    fun `search tool finds results by substring matching`() = runBlocking {
        val tool = SearchTool()
        val result1 = tool.execute("tell me about kotlin")
        assertTrue(result1.contains("Kotlin"), "should find Kotlin result")
        assertTrue(result1.contains("JetBrains"), "result should mention JetBrains")

        val result2 = tool.execute("coroutines")
        assertTrue(result2.contains("Coroutines"), "should find coroutines result")

        val result3 = tool.execute("unknown-term-xyz")
        assertTrue(result3.contains("No results"), "should return no-results message")
    }

    @Test
    fun `summarize tool formats markdown`() = runBlocking {
        val tool = SummarizeTool()
        val result = tool.execute("First result\nSecond result")
        assertTrue(result.contains("## Summary"), "should have heading")
        assertTrue(result.contains("First result"), "should include content")
        assertTrue(result.contains("Cogent"), "should mention source")
    }
}
