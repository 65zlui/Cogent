package com.cogent.examples.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// ================================================================
// Data types
// ================================================================

/**
 * A message in an LLM conversation.
 *
 * @property role    "system", "user", or "assistant"
 * @property content Message text content
 */
data class LlmMessage(
    val role: String,
    val content: String
)

/**
 * Response from an LLM chat completion call.
 *
 * @property content  The generated text, or null if the model returned no content
 * @property model    The model that generated the response
 * @property usage    Token usage statistics (may be null if API doesn't report it)
 */
data class LlmResponse(
    val content: String?,
    val model: String,
    val usage: LlmUsage? = null
)

/**
 * Token usage statistics from an LLM API response.
 */
data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ================================================================
// Error hierarchy
// ================================================================

/**
 * Base exception for all LLM API errors.
 */
open class LlmException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Authentication/authorization error (HTTP 401, 403).
 */
class LlmAuthException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : LlmException(message)

/**
 * Rate limit exceeded (HTTP 429).
 * Check [retryAfter] for the suggested wait time in seconds.
 */
class LlmRateLimitException(
    message: String,
    val retryAfter: Int,
    val responseBody: String
) : LlmException(message)

/**
 * Server-side error (HTTP 5xx).
 */
class LlmServerException(
    message: String,
    val statusCode: Int,
    val responseBody: String
) : LlmException(message)

/**
 * Network-level error (SSL handshake, connection timeout, IO failure).
 */
class LlmNetworkException(
    message: String,
    cause: Throwable
) : LlmException(message, cause)

// ================================================================
// LLM Client
// ================================================================

/**
 * OpenAI-compatible LLM client with streaming, retry, and error handling.
 *
 * Works with any provider that exposes an OpenAI-compatible chat completions API:
 *   - OpenAI       (https://api.openai.com/v1)
 *   - DeepSeek     (https://api.deepseek.com/v1)
 *   - Kimi/Moonshot (https://api.moonshot.cn/v1)
 *   - Any custom endpoint
 *
 * Uses [java.net.http.HttpClient] for HTTP transport and [org.json] for JSON.
 * Bypasses system proxy settings by default (some local proxies interfere with TLS).
 *
 * @param baseUrl          API base URL (e.g. "https://api.openai.com/v1")
 * @param apiKey           API key for authentication
 * @param model            Model identifier (e.g. "gpt-4o-mini", "deepseek-chat")
 * @param timeoutSeconds   Request timeout in seconds (default 15)
 * @param httpClient       Optional pre-configured [HttpClient] (for testing).
 *                         When null, a default client with TLS 1.2 and no proxy is built.
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutSeconds: Long = 15,
    private val httpClient: HttpClient? = null
) {
    private val defaultSslContext: SSLContext by lazy {
        try {
            SSLContext.getInstance("TLSv1.2").apply {
                init(null, null, SecureRandom())
            }
        } catch (_: Exception) {
            SSLContext.getDefault()
        }
    }

    private val defaultHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .sslContext(defaultSslContext)
        .proxy(ProxySelector.of(null)) // bypass system proxy (fixes local proxy interference)
        .build()

    private val client: HttpClient get() = httpClient ?: defaultHttpClient

    // ================================================================
    // Chat (non-streaming)
    // ================================================================

    /**
     * Send a chat completion request (non-streaming).
     *
     * Automatically retries on HTTP 429 with exponential backoff.
     *
     * @param messages    Conversation messages (system, user, assistant)
     * @param temperature Sampling temperature (0.0-2.0, default 0.7)
     * @return The model's response
     * @throws LlmAuthException on 401/403
     * @throws LlmRateLimitException on 429 (after retries exhausted)
     * @throws LlmServerException on 5xx
     * @throws LlmNetworkException on network/SSL errors
     */
    suspend fun chat(
        messages: List<LlmMessage>,
        temperature: Double = 0.7
    ): LlmResponse = withContext(Dispatchers.IO) {
        val body = buildRequest(messages, temperature, stream = false)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        logRequest(messages.size, stream = false)
        val response = retryWithBackoff {
            httpSend(request)
        }
        logResponse(response)

        parseResponse(response.body())
    }

    // ================================================================
    // Chat (streaming)
    // ================================================================

    /**
     * Send a chat completion request with SSE streaming.
     *
     * Tokens are delivered incrementally via [onToken] as they arrive.
     * The full accumulated response is returned once the stream completes.
     *
     * Automatically retries on HTTP 429 with exponential backoff.
     *
     * Each chunk's `delta.content` is passed to [onToken].
     * The [LlmResponse.content] contains the complete accumulated text.
     *
     * @param messages    Conversation messages (system, user, assistant)
     * @param onToken     Callback invoked for each content token (suspending, called on IO dispatcher)
     * @param temperature Sampling temperature (0.0-2.0, default 0.7)
     * @return The complete accumulated response
     * @throws LlmAuthException on 401/403
     * @throws LlmRateLimitException on 429 (after retries exhausted)
     * @throws LlmServerException on 5xx
     * @throws LlmNetworkException on network/SSL errors
     */
    suspend fun chatStream(
        messages: List<LlmMessage>,
        onToken: suspend (String) -> Unit,
        temperature: Double = 0.7
    ): LlmResponse = withContext(Dispatchers.IO) {
        val body = buildRequest(messages, temperature, stream = true)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        logRequest(messages.size, stream = true)

        // Wrap HTTP send + status check in retryWithBackoff.
        // Non-200 status codes throw typed exceptions that retryWithBackoff handles:
        //   - 429 → LlmRateLimitException → retry with backoff
        //   - 5xx → LlmServerException → NOT retried (server likely still failing)
        //   - 401/403 → LlmAuthException → NOT retried (auth won't magically work)
        // Network errors → LlmNetworkException → retry with exponential backoff
        val response = retryWithBackoff {
            val resp = httpSendStream(request)
            if (resp.statusCode() != 200) {
                val errorBody = try {
                    resp.body().bufferedReader().use { it.readText() }
                } catch (_: Exception) { "" }
                val retryAfter = parseRetryAfterHeader(resp)
                throw buildHttpException(resp.statusCode(), errorBody, retryAfter)
            }
            resp
        }
        logResponseStatus(response)

        val accumulated = StringBuilder()
        var responseModel = model

        response.body().bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                currentCoroutineContext().ensureActive()
                if (!line.startsWith("data: ")) {
                    line = reader.readLine()
                    continue
                }
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)
                    val choice = chunk.optJSONArray("choices")
                        ?.optJSONObject(0) ?: run { line = reader.readLine(); continue }
                    val delta = choice.optJSONObject("delta") ?: run { line = reader.readLine(); continue }
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        accumulated.append(content)
                        onToken(content)
                    }
                    // Capture model from first chunk that provides it
                    if (chunk.has("model") && !chunk.isNull("model")) {
                        responseModel = chunk.optString("model", model)
                    }
                } catch (_: JSONException) {
                    // Skip malformed chunks
                }

                line = reader.readLine()
            }
        }

        val fullText = accumulated.toString()
        println("[LLM] ← stream complete (${fullText.length} chars, model=$responseModel)")
        LlmResponse(
            content = fullText,
            model = responseModel,
            usage = null // usage not available in streaming
        )
    }

    // ================================================================
    // Retry with backoff
    // ================================================================

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        block: suspend () -> T
    ): T {
        var lastDelay = 1000L
        repeat(maxRetries + 1) { attempt ->
            try {
                currentCoroutineContext().ensureActive()
                return block()
            } catch (e: CancellationException) {
                throw e // propagate immediately, do not retry
            } catch (e: LlmRateLimitException) {
                if (attempt == maxRetries) throw e
                val delayMs = (e.retryAfter * 1000L).coerceIn(1000, 60000)
                println("[LLM] ← HTTP 429, retrying after ${delayMs}ms (attempt ${attempt + 1}/$maxRetries)")
                delay(delayMs)
                lastDelay *= 2
            } catch (e: LlmNetworkException) {
                if (attempt == maxRetries) throw e
                println("[LLM] ← network error, retrying in ${lastDelay}ms (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                delay(lastDelay)
                lastDelay *= 2
            }
        }
        throw IllegalStateException("Unreachable")
    }

    // ================================================================
    // HTTP helpers
    // ================================================================

    private fun httpSend(request: HttpRequest): HttpResponse<String> {
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: SSLHandshakeException) {
            val shortUrl = baseUrl.removePrefix("https://")
            throw LlmNetworkException(
                "SSL handshake failed with $shortUrl. " +
                    "This may be caused by a network proxy or TLS version mismatch.\n" +
                    "  Original: ${e.message}", e
            )
        } catch (e: HttpConnectTimeoutException) {
            throw LlmNetworkException(
                "Connection timed out connecting to $baseUrl. Check your network and firewall.", e
            )
        } catch (e: HttpTimeoutException) {
            throw LlmNetworkException(
                "Request timed out (${timeoutSeconds}s) for $baseUrl. The provider may be slow.", e
            )
        } catch (e: java.io.IOException) {
            throw LlmNetworkException(
                "Network error connecting to $baseUrl: ${e.message}", e
            )
        }
    }

    /**
     * Send a request and return the raw [InputStream] response.
     * Network/SSL/timeout errors are wrapped in [LlmNetworkException].
     */
    private fun httpSendStream(request: HttpRequest): HttpResponse<java.io.InputStream> {
        return try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: SSLHandshakeException) {
            val shortUrl = baseUrl.removePrefix("https://")
            throw LlmNetworkException(
                "SSL handshake failed with $shortUrl. " +
                    "This may be caused by a network proxy or TLS version mismatch.\n" +
                    "  Original: ${e.message}", e
            )
        } catch (e: HttpConnectTimeoutException) {
            throw LlmNetworkException(
                "Connection timed out connecting to $baseUrl. Check your network and firewall.", e
            )
        } catch (e: HttpTimeoutException) {
            throw LlmNetworkException(
                "Request timed out (${timeoutSeconds}s) for $baseUrl. The provider may be slow.", e
            )
        } catch (e: java.io.IOException) {
            throw LlmNetworkException(
                "Network error connecting to $baseUrl: ${e.message}", e
            )
        }
    }

    // ================================================================
    // Response parsing
    // ================================================================

    private fun logRequest(messageCount: Int, stream: Boolean) {
        val shortUrl = baseUrl.removePrefix("https://").removePrefix("http://")
        println("[LLM] → POST $shortUrl/chat/completions  model=$model messages=$messageCount stream=$stream")
    }

    private fun logResponse(response: HttpResponse<String>) {
        val elapsed = "" // timing not tracked here
        val bodyLen = response.body().length
        println("[LLM] ← HTTP ${response.statusCode()} (${bodyLen} chars)")
        if (response.statusCode() != 200) {
            println("[LLM] ← body: ${response.body().take(200)}")
        }
    }

    private fun logResponseStatus(response: HttpResponse<java.io.InputStream>) {
        println("[LLM] ← HTTP ${response.statusCode()} (stream)")
        if (response.statusCode() != 200) {
            println("[LLM] ← error response")
        }
    }

    /**
     * Parse the `Retry-After` header from an HTTP response.
     * Returns the default value (5) if the header is missing or unparseable.
     */
    private fun parseRetryAfterHeader(response: HttpResponse<*>): Int {
        return response.headers().firstValue("Retry-After")
            .map { it.toIntOrNull() ?: 5 }
            .orElse(5)
    }

    private fun buildHttpException(statusCode: Int, body: String, retryAfter: Int = 5): LlmException {
        return when (statusCode) {
            401, 403 -> LlmAuthException(
                "Authentication failed: HTTP $statusCode", statusCode, body
            )
            429 -> LlmRateLimitException("Rate limited: HTTP 429", retryAfter, body)
            in 500..599 -> LlmServerException(
                "Server error: HTTP $statusCode", statusCode, body
            )
            else -> LlmException(
                "LLM API returned HTTP $statusCode: ${body.take(100)}"
            )
        }
    }

    private fun parseResponse(json: String): LlmResponse {
        val root = JSONObject(json)

        // Parse first choice
        val choice = root.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val content = message.optString("content", null)?.takeIf { it != "null" }
        val finishReason = choice.optString("finish_reason", "")

        // Parse model (API may return a different model than requested)
        val responseModel = root.optString("model", model)

        // Parse usage stats if available
        val usage = if (root.has("usage") && !root.isNull("usage")) {
            val u = root.getJSONObject("usage")
            LlmUsage(
                promptTokens = u.optInt("prompt_tokens", 0),
                completionTokens = u.optInt("completion_tokens", 0),
                totalTokens = u.optInt("total_tokens", 0)
            )
        } else null

        return LlmResponse(
            content = content,
            model = responseModel,
            usage = usage
        )
    }

    // ================================================================
    // Request builder
    // ================================================================

    private fun buildRequest(
        messages: List<LlmMessage>,
        temperature: Double,
        stream: Boolean
    ): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("temperature", temperature)
        root.put("stream", stream)

        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        root.put("messages", messagesArray)

        return root.toString(2)
    }

    // ================================================================
    // Utilities
    // ================================================================

    /**
     * Human-readable description of this client (provider + model).
     * Used to surface LLM config in the execution timeline.
     */
    fun describe(): String {
        val shortUrl = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/v1")
        return "$shortUrl / $model"
    }
}

// ================================================================
// Provider configuration
// ================================================================

/**
 * Supported LLM providers with default connection details.
 *
 * Each provider maps to an environment variable for the API key:
 *   - OPENAI   → OPENAI_API_KEY
 *   - DEEPSEEK → DEEPSEEK_API_KEY
 *   - KIMI     → KIMI_API_KEY
 */
enum class LlmProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val envKey: String
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "OPENAI_API_KEY"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "DEEPSEEK_API_KEY"),
    KIMI("Kimi/Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "KIMI_API_KEY");
}

/**
 * Auto-detect which LLM provider is configured via environment variables.
 *
 * Checks [LlmProvider.envKey] for each provider and returns the first
 * match whose environment variable is present and non-blank.
 *
 * @return The detected provider, or null if no API key is configured
 */
fun detectLlmProvider(): LlmProvider? {
    return LlmProvider.entries.firstOrNull { p ->
        val value = System.getenv(p.envKey)
        value != null && value.isNotBlank()
    }
}

/**
 * Create an [LlmClient] from a provider configuration.
 *
 * The API key is resolved in this order:
 * 1. [apiKey] parameter (if non-null)
 * 2. Environment variable ([LlmProvider.envKey])
 * 3. Throws [IllegalArgumentException]
 *
 * @param provider The LLM provider to use. Defaults to auto-detection.
 * @param apiKey   Optional API key override. Uses env var if null.
 * @param model    Optional model override. Uses provider default if null.
 * @throws IllegalArgumentException if no API key can be resolved.
 */
fun createLlmClient(
    provider: LlmProvider = detectLlmProvider()
        ?: throw IllegalArgumentException(
            "No LLM provider detected. Set one of: " +
                LlmProvider.entries.joinToString(", ") { it.envKey }
        ),
    apiKey: String? = null,
    model: String? = null
): LlmClient {
    val resolvedKey = apiKey ?: System.getenv(provider.envKey)
        ?: throw IllegalArgumentException(
            "${provider.envKey} is not set. " +
                "Set it as an environment variable or pass the API key directly."
        )
    return LlmClient(
        baseUrl = provider.baseUrl,
        apiKey = resolvedKey,
        model = model ?: provider.defaultModel
    )
}
