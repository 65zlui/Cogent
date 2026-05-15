package com.cogent.inspector

import com.cogent.runtime.RuntimeDebugger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Web Inspector for the Cogent execution observability runtime.
 *
 * Serves a D3.js-based DAG visualization frontend on localhost:3000.
 * Provides REST API endpoints for querying traces, graphs, and state.
 *
 * Usage:
 * ```kotlin
 * val inspector = InspectorServer(runtime.debugger(), port = 3000)
 * inspector.start()
 * ```
 */
class InspectorServer(
    private val debugger: RuntimeDebugger,
    private val port: Int = 3000
) {
    private var server: HttpServer? = null

    fun start() {
        server = HttpServer.create(InetSocketAddress(port), 0)
        registerRoutes(server!!)
        server!!.executor = Executors.newSingleThreadExecutor()
        server!!.start()
        println("[inspector] Web Inspector running at http://localhost:$port")
        println("[inspector] API:   http://localhost:$port/api/traces")
        println("[inspector] Front: http://localhost:$port/")
    }

    fun stop() {
        server?.stop(0)
    }

    // ================================================================
    // Route registration
    // ================================================================

    private fun registerRoutes(server: HttpServer) {
        // Order matters: most specific prefix first
        server.createContext("/api/traces") { h -> routeTraces(h) }
        server.createContext("/api/nodes") { h -> routeNodes(h) }
        server.createContext("/api/state") { h -> routeState(h) }
        server.createContext("/") { h -> routeStatic(h) }
    }

    // ================================================================
    // /api/traces — list traces and retrieve graphs
    // ================================================================

    /**
     * GET  /api/traces                  → list trace IDs
     * GET  /api/traces/{traceId}        → full TimelineGraph
     * GET  /api/traces/{traceId}/critical-path → critical path nodes
     */
    private fun routeTraces(exchange: HttpExchange) {
        cors(exchange)
        if (exchange.requestMethod == "OPTIONS") return

        if (exchange.requestMethod != "GET") {
            return sendJson(exchange, 405, """{"error":"Method not allowed"}""")
        }

        val path = exchange.requestURI.path

        // List all trace IDs
        if (path == "/api/traces" || path == "/api/traces/") {
            val traceIds = debugger.traceIds()
            return sendJson(exchange, 200, JSONArray(traceIds).toString())
        }

        // Parse /api/traces/{traceId}[/critical-path]
        val remaining = path.removePrefix("/api/traces/")
        val parts = remaining.split("/").filter { it.isNotBlank() }
        when {
            parts.size == 1 -> {
                // Full graph
                val traceId = parts[0]
                val graph = debugger.timeline(traceId)
                if (graph == null) {
                    return sendJson(exchange, 404, """{"error":"Trace not found: $traceId"}""")
                }
                val json = JsonMapper.run { graph.toJson() }
                sendJson(exchange, 200, json.toString())
            }
            parts.size == 2 && parts[1] == "critical-path" -> {
                // Critical path
                val traceId = parts[0]
                val query = debugger.query(traceId)
                if (query == null) {
                    return sendJson(exchange, 404, """{"error":"Trace not found: $traceId"}""")
                }
                val graph = debugger.timeline(traceId)!!
                val criticalPath = query.criticalPath()
                val json = JSONArray(criticalPath.map { node ->
                    val j = JsonMapper.run { node.toJson() }
                    j.put("orderIndex", graph.nodes.indexOf(node))
                    j
                })
                sendJson(exchange, 200, json.toString())
            }
            else -> sendJson(exchange, 404, """{"error":"Invalid path: $path"}""")
        }
    }

    // ================================================================
    // /api/nodes/{nodeId} — single node with children and parents
    // ================================================================

    /**
     * GET /api/nodes/{nodeId} → { node, children[], parents[] }
     */
    private fun routeNodes(exchange: HttpExchange) {
        cors(exchange)
        if (exchange.requestMethod == "OPTIONS") return

        if (exchange.requestMethod != "GET") {
            return sendJson(exchange, 405, """{"error":"Method not allowed"}""")
        }

        val path = exchange.requestURI.path
        val nodeId = path.removePrefix("/api/nodes/")
            .trim('/')
            .ifBlank { return sendJson(exchange, 400, """{"error":"Missing nodeId"}""") }

        val node = debugger.inspect(nodeId)
        if (node == null) {
            return sendJson(exchange, 404, """{"error":"Node not found: $nodeId"}""")
        }

        val children = debugger.children(nodeId)
        val parents = debugger.parents(nodeId)

        val json = JSONObject().apply {
            put("node", JsonMapper.run { node.toJson() })
            put("children", JSONArray(children.map { JsonMapper.run { it.toJson() } }))
            put("parents", JSONArray(parents.map { JsonMapper.run { it.toJson() } }))
        }
        sendJson(exchange, 200, json.toString())
    }

    // ================================================================
    // /api/state — state reconstruction at a version
    // ================================================================

    /**
     * POST /api/state
     * Body: { "traceId": "...", "stateVersion": <long> }
     * Response: { "traceId": "...", "stateVersion": <long>, "state": { ... } }
     */
    private fun routeState(exchange: HttpExchange) {
        cors(exchange)
        if (exchange.requestMethod == "OPTIONS") return

        if (exchange.requestMethod != "POST") {
            return sendJson(exchange, 405, """{"error":"Method not allowed"}""")
        }

        val body = readBody(exchange)
        val request = JSONObject(body)
        val traceId = request.optString("traceId", "")
        val stateVersion = request.optLong("stateVersion", -1)

        if (traceId.isBlank() || stateVersion < 0) {
            return sendJson(exchange, 400, """{"error":"traceId and stateVersion required"}""")
        }

        val state = StateReconstructor.reconstruct(debugger, traceId, stateVersion)
        if (state == null) {
            return sendJson(exchange, 404, """{"error":"Trace not found: $traceId"}""")
        }

        val stateJson = JSONObject()
        for ((key, value) in state) {
            stateJson.put(key, JsonMapper.toSafeJson(value))
        }

        val json = JSONObject().apply {
            put("traceId", traceId)
            put("stateVersion", stateVersion)
            put("state", stateJson)
        }
        sendJson(exchange, 200, json.toString())
    }

    // ================================================================
    // Static file serving
    // ================================================================

    private val staticCache = mutableMapOf<String, ByteArray>()

    /**
     * Serve files from classpath:/inspector/ (src/main/resources/inspector/).
     *
     * GET  /           → /inspector/index.html
     * GET  /index.html → /inspector/index.html
     * GET  /file.js    → /inspector/file.js
     */
    private fun routeStatic(exchange: HttpExchange) {
        cors(exchange)

        var path = exchange.requestURI.path
        if (path == "/" || path.isBlank()) path = "/index.html"

        // Security: prevent directory traversal
        if (path.contains("..")) {
            return sendJson(exchange, 400, """{"error":"Invalid path"}""")
        }

        val resourcePath = "/inspector$path"
        val bytes = staticCache.getOrPut(resourcePath) {
            val stream = this::class.java.getResourceAsStream(resourcePath)
                ?: return@getOrPut ByteArray(0)
            stream.readBytes()
        }

        if (bytes.isEmpty()) {
            return sendJson(exchange, 404, """{"error":"Not found: $path"}""")
        }

        val contentType = when {
            path.endsWith(".html") -> "text/html; charset=utf-8"
            path.endsWith(".js")   -> "application/javascript; charset=utf-8"
            path.endsWith(".css")  -> "text/css; charset=utf-8"
            path.endsWith(".svg")  -> "image/svg+xml"
            path.endsWith(".png")  -> "image/png"
            path.endsWith(".json") -> "application/json"
            else                   -> "application/octet-stream"
        }

        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    // ================================================================
    // HTTP helpers
    // ================================================================

    private fun cors(exchange: HttpExchange) {
        val headers = exchange.responseHeaders
        headers.set("Access-Control-Allow-Origin", "*")
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        headers.set("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun sendJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.responseBody.close()
    }

    private fun readBody(exchange: HttpExchange): String {
        if (exchange.requestMethod == "GET" || exchange.requestMethod == "OPTIONS") {
            return ""
        }
        val baos = ByteArrayOutputStream()
        exchange.requestBody.copyTo(baos)
        return baos.toString(StandardCharsets.UTF_8)
    }
}
