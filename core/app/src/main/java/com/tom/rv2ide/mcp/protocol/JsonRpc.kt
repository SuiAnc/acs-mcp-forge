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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Minimal JSON-RPC 2.0 helpers used by the MCP transport.
 *
 * This file intentionally has no Android dependencies so that it can be compiled and tested on a
 * plain JVM.
 *
 * @author AndroidCodeStudio
 */
object JsonRpc {

  const val VERSION = "2.0"

  const val PARSE_ERROR = -32700
  const val INVALID_REQUEST = -32600
  const val METHOD_NOT_FOUND = -32601
  const val INVALID_PARAMS = -32602
  const val INTERNAL_ERROR = -32603

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /** Parses the given JSON text and ensures that it is a JSON object. */
  fun parseObject(text: String): JsonObject {
    val element =
        try {
          json.parseToJsonElement(text)
        } catch (e: Exception) {
          throw JsonRpcException(PARSE_ERROR, "Parse error: ${e.message ?: "malformed JSON"}")
        }

    if (element is JsonObject) {
      return element
    }

    if (element is kotlinx.serialization.json.JsonArray) {
      // Batches are handled by the caller, which re-invokes this method per element.
      throw JsonRpcBatch(element)
    }

    throw JsonRpcException(INVALID_REQUEST, "Invalid JSON-RPC request: expected an object")
  }

  fun parseAny(text: String): JsonElement {
    return try {
      json.parseToJsonElement(text)
    } catch (e: Exception) {
      throw JsonRpcException(PARSE_ERROR, "Parse error: ${e.message ?: "malformed JSON"}")
    }
  }

  fun stringify(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

  fun idOf(request: JsonObject): JsonElement? = request["id"]

  fun methodOf(request: JsonObject): String? {
    val method = request["method"] as? JsonPrimitive ?: return null
    return method.contentOrNullSafe()
  }

  fun paramsOf(request: JsonObject): JsonObject {
    return request["params"] as? JsonObject ?: JsonObject(emptyMap())
  }

  /** A notification is a request without an `id` member. */
  fun isNotification(request: JsonObject): Boolean = !request.containsKey("id")

  fun success(id: JsonElement?, result: JsonElement): JsonObject {
    return buildJsonObject {
      put("jsonrpc", VERSION)
      put("id", id ?: JsonNull)
      put("result", result)
    }
  }

  fun failure(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject {
    return buildJsonObject {
      put("jsonrpc", VERSION)
      put("id", id ?: JsonNull)
      put(
          "error",
          buildJsonObject {
            put("code", code)
            put("message", message)
            data?.let { put("data", it) }
          },
      )
    }
  }

  fun notification(method: String, params: JsonObject? = null): JsonObject {
    return buildJsonObject {
      put("jsonrpc", VERSION)
      put("method", method)
      params?.let { put("params", it) }
    }
  }

  private fun JsonPrimitive.contentOrNullSafe(): String? {
    return if (this is JsonNull) null else content
  }

  /** Thrown when the parsed payload is a JSON array (JSON-RPC batch). */
  class JsonRpcBatch(val elements: kotlinx.serialization.json.JsonArray) : Exception()
}

/** A JSON-RPC error which carries a protocol error code. */
class JsonRpcException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null,
) : Exception(message)

/** Thrown by tool implementations when the given arguments are invalid. */
class McpArgumentException(message: String) : IllegalArgumentException(message)

/** Utility accessors for [JsonObject] values. */
object Args {

  fun string(args: JsonObject, name: String): String {
    return optionalString(args, name)
        ?: throw McpArgumentException("Missing required argument '$name'")
  }

  fun optionalString(args: JsonObject, name: String): String? {
    val element = args[name] ?: return null
    if (element is JsonNull) return null
    val primitive = element.jsonPrimitive
    val content = primitive.content
    return if (content.isBlank()) null else content
  }

  fun integer(args: JsonObject, name: String, default: Int): Int {
    val element = args[name] ?: return default
    if (element is JsonNull) return default
    return element.jsonPrimitive.content.toIntOrNull()
        ?: throw McpArgumentException("Argument '$name' must be an integer")
  }

  fun optionalBoolean(args: JsonObject, name: String): Boolean? {
    val element = args[name] ?: return null
    if (element is JsonNull) return null
    return when (element.jsonPrimitive.content.lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw McpArgumentException("Argument '$name' must be a boolean")
    }
  }

  fun boolean(args: JsonObject, name: String, default: Boolean): Boolean {
    return optionalBoolean(args, name) ?: default
  }

  fun stringList(args: JsonObject, name: String): List<String> {
    val element = args[name] ?: return emptyList()
    if (element is JsonNull) return emptyList()
    val array =
        element as? kotlinx.serialization.json.JsonArray
            ?: throw McpArgumentException("Argument '$name' must be an array of strings")
    return array.map { it.jsonPrimitive.content }
  }
}
