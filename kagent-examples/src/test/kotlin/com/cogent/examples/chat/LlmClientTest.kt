package com.cogent.examples.chat

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for [LlmClient] error hierarchy and HTTP handling.
 * Uses a local [HttpServer] to simulate LLM API responses.
 */
class LlmClientTest {

    /** Create an [LlmClient] pointed at a local server with a custom HttpClient. */
    private fun testClient(server: HttpServer): LlmClient {
        val port = server.address.port
        return LlmClient(
            baseUrl = "http://localhost:$port/v1",
            apiKey = "test-key",
            model = "test-model",
            httpClient = HttpClient.newBuilder().build()
        )
    }

    @Test
    fun `chat throws LlmAuthException on 401`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(401, 0)
            exchange.responseBody.write("""{"error":"unauthorized"}""".toByteArray())
            exchange.responseBody.close()
        }
        server.start()
        try {
            val client = testClient(server)
            assertFailsWith<LlmAuthException> {
                client.chat(listOf(LlmMessage("user", "hello")))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat throws LlmAuthException on 403`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(403, 0)
            exchange.responseBody.write("""{"error":"forbidden"}""".toByteArray())
            exchange.responseBody.close()
        }
        server.start()
        try {
            val client = testClient(server)
            assertFailsWith<LlmAuthException> {
                client.chat(listOf(LlmMessage("user", "hello")))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat throws LlmServerException on 500`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.write("""{"error":"internal"}""".toByteArray())
            exchange.responseBody.close()
        }
        server.start()
        try {
            val client = testClient(server)
            assertFailsWith<LlmServerException> {
                client.chat(listOf(LlmMessage("user", "hello")))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chat succeeds on 200`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = """
                {
                    "choices": [{
                        "message": { "content": "Hello from test!" },
                        "finish_reason": "stop"
                    }],
                    "model": "test-model",
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15
                    }
                }
            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.length.toLong())
            exchange.responseBody.write(body.toByteArray())
            exchange.responseBody.close()
        }
        server.start()
        try {
            val client = testClient(server)
            val resp = client.chat(listOf(LlmMessage("user", "hello")))
            assertEquals("Hello from test!", resp.content)
            assertEquals("test-model", resp.model)
            val usage = resp.usage
            assertNotNull(usage)
            assertEquals(10, usage.promptTokens)
            assertEquals(5, usage.completionTokens)
            assertEquals(15, usage.totalTokens)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `chatStream calls onToken for each delta`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val chunks = """
data: {"choices":[{"delta":{"content":"Hello"}}]}

data: {"choices":[{"delta":{"content":" "}}]}

data: {"choices":[{"delta":{"content":"world"}}]}

data: {"choices":[{"delta":{}}]}

data: [DONE]

            """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = chunks.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val client = testClient(server)
            val tokens = mutableListOf<String>()
            val resp = client.chatStream(
                messages = listOf(LlmMessage("user", "say hi")),
                onToken = { token -> tokens.add(token) }
            )
            assertEquals(listOf("Hello", " ", "world"), tokens, "onToken should be called for each delta")
            assertEquals("Hello world", resp.content, "response should accumulate all tokens")
            assertEquals("test-model", resp.model, "model should default to configured model")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `describe returns readable provider info`() {
        val client = LlmClient(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "test",
            model = "gpt-4o-mini",
            httpClient = HttpClient.newBuilder().build()
        )
        assertEquals("api.openai.com / gpt-4o-mini", client.describe())
    }
}
