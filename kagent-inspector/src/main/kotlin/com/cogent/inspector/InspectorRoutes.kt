package com.cogent.inspector

import com.cogent.runtime.*
import org.json.JSONArray
import org.json.JSONObject

// ================================================================
// JSON serialization helpers for timeline graph types.
// ================================================================

object JsonMapper {

    fun RuntimeEvent.toJson(): JSONObject = when (this) {
        is RuntimeEvent.RunStart -> JSONObject().apply {
            put("type", "RunStart")
            put("traceId", traceId)
        }
        is RuntimeEvent.RunEnd -> JSONObject().apply {
            put("type", "RunEnd")
            put("traceId", traceId)
        }
        is RuntimeEvent.StepStart -> JSONObject().apply {
            put("type", "StepStart")
            put("stepId", stepId)
        }
        is RuntimeEvent.StepEnd -> JSONObject().apply {
            put("type", "StepEnd")
            put("stepId", stepId)
        }
        is RuntimeEvent.MemoryChange -> JSONObject().apply {
            put("type", "MemoryChange")
            put("key", key)
            put("oldValue", toSafeJson(oldValue))
            put("newValue", toSafeJson(newValue))
        }
        is RuntimeEvent.DerivedRecompute -> JSONObject().apply {
            put("type", "DerivedRecompute")
            put("key", key)
        }
        is RuntimeEvent.ToolCall -> JSONObject().apply {
            put("type", "ToolCall")
            put("tool", tool)
            put("input", input)
        }
        is RuntimeEvent.ToolResult -> JSONObject().apply {
            put("type", "ToolResult")
            put("tool", tool)
            put("output", output)
        }
        is RuntimeEvent.StreamStart -> JSONObject().apply {
            put("type", "StreamStart")
            put("provider", provider ?: JSONObject.NULL)
            put("model", model ?: JSONObject.NULL)
        }
        is RuntimeEvent.StreamDelta -> JSONObject().apply {
            put("type", "StreamDelta")
            put("accumulated", accumulated)
            put("deltaContent", deltaContent)
        }
        is RuntimeEvent.StreamEnd -> JSONObject().apply {
            put("type", "StreamEnd")
            put("totalLength", totalLength)
            put("model", model ?: JSONObject.NULL)
        }
    }

    fun TimelineNode.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("event", event.toJson())
        put("timestamp", timestamp)
        put("traceId", traceId)
        put("parentId", parentId ?: JSONObject.NULL)
        put("stateVersion", stateVersion)
    }

    fun TimelineEdge.toJson(): JSONObject = JSONObject().apply {
        put("fromNodeId", fromNodeId)
        put("toNodeId", toNodeId)
        put("type", type.name)
    }

    fun TimelineGraph.toJson(): JSONObject = JSONObject().apply {
        put("traceId", traceId)
        put("nodeCount", nodeCount)
        put("startTime", startTime)
        put("endTime", endTime)
        put("nodes", JSONArray(nodes.map { it.toJson() }))
        put("edges", JSONArray(edges.map { it.toJson() }))
    }

    /**
     * Convert an arbitrary value to a JSON-safe representation.
     * Handles null, primitives, strings, maps, and iterables.
     */
    fun toSafeJson(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is String -> value
            is Number -> value
            is Boolean -> value
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    obj.put(k.toString(), toSafeJson(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(toSafeJson(item))
                }
                arr
            }
            is Array<*> -> {
                val arr = JSONArray()
                for (item in value) {
                    arr.put(toSafeJson(item))
                }
                arr
            }
            else -> value.toString()
        }
    }
}

// ================================================================
// State reconstruction from MemoryChange events.
// ================================================================

object StateReconstructor {

    /**
     * Replay MemoryChange events up to [stateVersion] to reconstruct state.
     *
     * Uses [TimelineGraph] nodes which carry stateVersion metadata.
     * No changes to kagent-debugger required — works purely through
     * the existing [RuntimeDebugger.timeline] API.
     *
     * @return Map of key → value at the requested version, or null if trace not found.
     */
    fun reconstruct(
        debugger: RuntimeDebugger,
        traceId: String,
        stateVersion: Long
    ): Map<String, Any?>? {
        val graph = debugger.timeline(traceId) ?: return null
        val state = mutableMapOf<String, Any?>()
        for (node in graph.nodes) {
            if (node.stateVersion > stateVersion) break
            if (node.event is RuntimeEvent.MemoryChange) {
                val mc = node.event as RuntimeEvent.MemoryChange
                state[mc.key] = mc.newValue
            }
        }
        return state
    }

    /**
     * Reconstruct state for multiple traces, for cross-trace comparison.
     */
    fun reconstructAll(
        debugger: RuntimeDebugger,
        traceIds: List<String>,
        stateVersion: Long
    ): Map<String, Map<String, Any?>> {
        val result = mutableMapOf<String, Map<String, Any?>>()
        for (traceId in traceIds) {
            val state = reconstruct(debugger, traceId, stateVersion)
            if (state != null) {
                result[traceId] = state
            }
        }
        return result
    }
}
