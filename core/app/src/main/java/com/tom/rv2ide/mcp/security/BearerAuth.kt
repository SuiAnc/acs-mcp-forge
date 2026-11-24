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

package com.tom.rv2ide.mcp.security

import com.tom.rv2ide.mcp.server.HttpRequest

/**
 * Validates the bearer token configured in the MCP server settings.
 *
 * An empty token disables authentication, which is the default so that the server can be used
 * right after enabling it on the local device.
 *
 * @author AndroidCodeStudio
 */
object BearerAuth {

  /**
   * Returns `true` if the request is allowed to proceed.
   *
   * @param expectedToken the token from the preferences; blank means "no authentication".
   */
  fun isAuthorized(request: HttpRequest, expectedToken: String): Boolean {
    val token = expectedToken.trim()
    if (token.isEmpty()) return true

    val header = request.header("authorization")
    if (header != null) {
      val value = header.trim()
      val prefix = "Bearer "
      if (value.startsWith(prefix, ignoreCase = true)) {
        return constantTimeEquals(value.substring(prefix.length).trim(), token)
      }
      return constantTimeEquals(value, token)
    }

    // Fall back to a query parameter so that browsers can be used for a quick check.
    val queryToken = request.queryParameter("token")
    return queryToken != null && constantTimeEquals(queryToken, token)
  }

  /** Compares two strings without leaking the length of the matching prefix. */
  fun constantTimeEquals(left: String, right: String): Boolean {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    if (a.size != b.size) return false
    var result = 0
    for (index in a.indices) {
      result = result or (a[index].toInt() xor b[index].toInt())
    }
    return result == 0
  }
}
