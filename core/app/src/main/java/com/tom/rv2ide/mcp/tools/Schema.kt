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

package com.tom.rv2ide.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Small helpers to build JSON schemas for tool arguments.
 *
 * @author AndroidCodeStudio
 */
object Schema {

  fun objectSchema(
      properties: List<Pair<String, JsonObject>>,
      required: List<String> = emptyList(),
  ): JsonObject {
    return buildJsonObject {
      put("type", "object")
      put("properties", JsonObject(LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply {
        properties.forEach { (name, schema) -> put(name, schema) }
      }))
      if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
      }
    }
  }

  fun string(description: String, vararg enumValues: String): JsonObject {
    return buildJsonObject {
      put("type", "string")
      put("description", description)
      if (enumValues.isNotEmpty()) {
        put("enum", buildJsonArray { enumValues.forEach { add(JsonPrimitive(it)) } })
      }
    }
  }

  fun boolean(description: String, default: Boolean? = null): JsonObject {
    return buildJsonObject {
      put("type", "boolean")
      put("description", description)
      default?.let { put("default", JsonPrimitive(it)) }
    }
  }

  fun integer(description: String, default: Int? = null): JsonObject {
    return buildJsonObject {
      put("type", "integer")
      put("description", description)
      default?.let { put("default", JsonPrimitive(it)) }
    }
  }

  fun stringArray(description: String): JsonObject {
    return buildJsonObject {
      put("type", "array")
      put("description", description)
      put("items", buildJsonObject { put("type", "string") })
    }
  }
}
