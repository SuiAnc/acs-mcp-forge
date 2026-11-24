/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.mcp.protocol

import com.tom.rv2ide.mcp.server.HttpCodec
import com.tom.rv2ide.mcp.server.HttpRequest
import com.tom.rv2ide.mcp.server.HttpResponse
import com.tom.rv2ide.mcp.tools.McpPermission
import com.tom.rv2ide.mcp.tools.McpTool
import com.tom.rv2ide.mcp.tools.McpToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Implements the MCP `initialize`, `ping`, `tools/list` and `tools/call` methods on top of the
 * JSON-RPC helpers in [JsonRpc].
 *
 * The transport is the MCP Streamable HTTP transport: every JSON-RPC message is sent with a `POST`
 * request to `/mcp`, and single responses are returned as `application/json`.
 *
 * @author AndroidCodeStudio
 */
class McpProtocol(
    private val serverName: String,
    private val serverVersion: String,
    private val provider: ToolProvider,
    private val permissionGate: PermissionGate = PermissionGate.ALLOW_ALL,
    private val logger: (String) -> Unit = {},
) {

  /** Provides the tool list and the execution context for a tool. */
  interface ToolProvider {

    fun availableTools(): List<McpTool>

    fun contextFor(tool: McpTool): McpToolContext
  }

  /** Decides whether a tool may run based on the switches in the settings. */
  fun interface PermissionGate {

    fun isGranted(permission: McpPermission): Boolean

    companion object {

      val ALLOW_ALL = PermissionGate { true }
    }
  }

  /** Handles a complete HTTP exchange. */
  fun handleHttp(request: HttpRequest): HttpResponse {
    val rawPath = request.path.trimEnd('/')

    // MCP clients are frequently configured with the bare base URL, so "/" is accepted as the MCP
    // endpoint as well.
    val isMcpEndpoint = rawPath.isEmpty() || rawPath == "/mcp"

    return when {
      request.method == "GET" &&
          (rawPath == "/health" || rawPath == "/mcp/health" || rawPath.isEmpty()) -> {
        healthResponse()
      }
      request.method == "OPTIONS" -> {
        HttpResponse.empty(
            204,
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Headers" to "Authorization, Content-Type, Mcp-Session-Id",
                "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
            ),
        )
      }
      !isMcpEndpoint ->
          HttpResponse.notFound(
              "Unknown endpoint: ${request.path}. Use POST /mcp for MCP requests or GET /health."
          )
      request.method == "GET" -> {
        // Server initiated SSE streams are not supported; clients must use POST /mcp.
        HttpResponse.json(
            405,
            """{"error":"method_not_allowed","message":"Use POST /mcp for MCP requests"}""",
            mapOf("Allow" to "POST, OPTIONS"),
        )
      }
      request.method != "POST" -> {
        HttpResponse.json(
            405,
            """{"error":"method_not_allowed","message":"Only POST is supported on /mcp"}""",
            mapOf("Allow" to "POST, OPTIONS"),
        )
      }
      else -> handlePost(request)
    }
  }

  private fun wantsEventStream(request: HttpRequest): Boolean {
    val accept = request.header("accept")?.lowercase() ?: return false
    return "text/event-stream" in accept && "application/json" !in accept
  }

  private fun healthResponse(): HttpResponse {
    val tools = provider.availableTools()
    val body =
        buildJsonObject {
          put("status", "ok")
          put("name", serverName)
          put("version", serverVersion)
          put("transport", "streamable-http")
          put("tools", tools.size)
          put(
              "permissions",
              buildJsonObject {
                put("read", permissionGate.isGranted(McpPermission.READ))
                put("write", permissionGate.isGranted(McpPermission.WRITE))
                put("build", permissionGate.isGranted(McpPermission.BUILD))
              },
          )
        }
    return HttpResponse.json(200, body.toString(), CORS_HEADERS)
  }

  private fun handlePost(request: HttpRequest): HttpResponse {
    val payload = request.bodyText()
    if (payload.isBlank()) {
      return HttpResponse.badRequest("Empty request body")
    }

    val response =
        try {
          handlePayload(payload)
        } catch (e: JsonRpcException) {
          JsonRpc.failure(null, e.code, e.message)
        } catch (e: Exception) {
          logger("Unexpected failure: ${e::class.java.simpleName}: ${e.message}")
          JsonRpc.failure(null, JsonRpc.INTERNAL_ERROR, e.message ?: "Internal error")
        }

    if (response == null) {
      // Only notifications were sent; acknowledge without a body.
      return HttpResponse.empty(202, CORS_HEADERS)
    }

    val payloadText = JsonRpc.stringify(response)
    if (wantsEventStream(request)) {
      // Some clients only accept a server sent event payload.
      val event = buildString {
        append("event: message\n")
        append("data: ").append(payloadText).append("\n\n")
      }
      return HttpResponse(
          200,
          event.toByteArray(Charsets.UTF_8),
          "text/event-stream; charset=utf-8",
          CORS_HEADERS,
      )
    }

    return HttpResponse.json(200, payloadText, CORS_HEADERS)
  }

  /**
   * Handles a raw JSON-RPC payload.
   *
   * @return the response payload, or `null` when the payload only contained notifications.
   */
  fun handlePayload(payload: String): JsonElement? {
    val element = JsonRpc.parseAny(payload)

    if (element is JsonArray) {
      if (element.isEmpty()) {
        throw JsonRpcException(JsonRpc.INVALID_REQUEST, "Empty JSON-RPC batch")
      }
      val responses = ArrayList<JsonObject>()
      element.forEach { entry ->
        if (entry !is JsonObject) {
          responses.add(JsonRpc.failure(null, JsonRpc.INVALID_REQUEST, "Batch entry must be an object"))
        } else {
          handleObject(entry)?.let { responses.add(it) }
        }
      }
      if (responses.isEmpty()) return null
      return buildJsonArray { responses.forEach { add(it) } }
    }

    if (element !is JsonObject) {
      throw JsonRpcException(JsonRpc.INVALID_REQUEST, "Expected a JSON object")
    }

    return handleObject(element)
  }

  private fun handleObject(request: JsonObject): JsonObject? {
    val id = JsonRpc.idOf(request)
    val method = JsonRpc.methodOf(request)
    if (method == null) {
      return JsonRpc.failure(id, JsonRpc.INVALID_REQUEST, "Missing 'method' member")
    }

    val isNotification = JsonRpc.isNotification(request)

    return when (method) {
      "initialize" -> if (isNotification) null else JsonRpc.success(id, initializeResult(request))
      "notifications/initialized",
      "notifications/cancelled" -> null
      "ping" -> if (isNotification) null else JsonRpc.success(id, JsonObject(emptyMap()))
      "tools/list" ->
          if (isNotification) null else JsonRpc.success(id, toolsListResult())
      "tools/call" -> if (isNotification) null else handleToolCall(id, request)
      else -> {
        if (isNotification) {
          null
        } else {
          JsonRpc.failure(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: $method")
        }
      }
    }
  }

  private fun initializeResult(request: JsonObject): JsonObject {
    val requested = Args.optionalString(JsonRpc.paramsOf(request), "protocolVersion")
    val negotiated =
        if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
          requested
        } else {
          DEFAULT_PROTOCOL_VERSION
        }

    return buildJsonObject {
      put("protocolVersion", negotiated)
      put(
          "capabilities",
          buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
          },
      )
      put(
          "serverInfo",
          buildJsonObject {
            put("name", serverName)
            put("version", serverVersion)
          },
      )
      put(
          "instructions",
          "Android Code Studio MCP server. Use file_* tools to browse and edit the opened project " +
              "and build_* tools to run Gradle builds.",
      )
    }
  }

  private fun toolsListResult(): JsonObject {
    val tools =
        provider.availableTools().filter { permissionGate.isGranted(it.permission) }

    return buildJsonObject {
      put(
          "tools",
          buildJsonArray {
            tools.forEach { tool ->
              add(
                  buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                  },
              )
            }
          },
      )
    }
  }

  private fun handleToolCall(id: JsonElement?, request: JsonObject): JsonObject {
    val params = JsonRpc.paramsOf(request)
    val name = Args.optionalString(params, "name")
    if (name == null) {
      return JsonRpc.failure(id, JsonRpc.INVALID_PARAMS, "Missing tool 'name'")
    }

    val tool = provider.availableTools().firstOrNull { it.name == name }
    if (tool == null) {
      return JsonRpc.failure(id, JsonRpc.INVALID_PARAMS, "Unknown tool: $name")
    }

    if (!permissionGate.isGranted(tool.permission)) {
      return JsonRpc.success(
          id,
          toolResult(
              "Tool '$name' is disabled. Enable it in Settings > Developer options > MCP server.",
              isError = true,
          ),
      )
    }

    val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

    val startedAt = System.currentTimeMillis()
    val result =
        try {
          tool.call(arguments, provider.contextFor(tool))
        } catch (e: McpArgumentException) {
          com.tom.rv2ide.mcp.tools.McpToolResult.failure(e.message ?: "Invalid arguments")
        } catch (e: Exception) {
          com.tom.rv2ide.mcp.tools.McpToolResult.failure(
              "${e::class.java.simpleName}: ${e.message ?: "unexpected error"}"
          )
        }
    val duration = System.currentTimeMillis() - startedAt

    logger(
        "tools/call name=$name durationMs=$duration isError=${result.isError} " +
            "args=${summarize(arguments)}"
    )

    return JsonRpc.success(id, toolResult(result.text, result.isError))
  }

  private fun toolResult(text: String, isError: Boolean): JsonObject {
    return buildJsonObject {
      put(
          "content",
          buildJsonArray {
            add(
                buildJsonObject {
                  put("type", "text")
                  put("text", text)
                },
            )
          },
      )
      put("isError", isError)
    }
  }

  private fun summarize(arguments: JsonObject): String {
    val text =
        arguments.entries.joinToString(", ") { (key, value) ->
          val rendered =
              when (value) {
                is JsonNull -> "null"
                is JsonPrimitive -> value.content
                else -> "<${value::class.java.simpleName}>"
              }
          "$key=${rendered.take(80)}"
        }
    return text.take(300)
  }

  companion object {

    const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"

    val SUPPORTED_PROTOCOL_VERSIONS =
        listOf("2024-11-05", "2025-03-26", "2025-06-18")

    private val CORS_HEADERS =
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "Authorization, Content-Type, Mcp-Session-Id",
            "Access-Control-Expose-Headers" to "Mcp-Session-Id",
        )

    /** Exposed for tests: maximum accepted request body size. */
    val maxBodyBytes: Int
      get() = HttpCodec.MAX_BODY_BYTES
  }
}
