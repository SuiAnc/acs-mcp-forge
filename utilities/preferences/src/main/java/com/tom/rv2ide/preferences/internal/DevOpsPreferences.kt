/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.preferences.internal

/** @author Akash Yadav */
@Suppress("MemberVisibilityCanBePrivate")
object DevOpsPreferences {

  const val KEY_DEVOPTS = "ide.prefs.developerOptions"
  const val KEY_DEVOPTS_DEBUGGING = "${KEY_DEVOPTS}.debugging"
  const val KEY_DEVOPTS_DEBUGGING_DUMPLOGS = "${KEY_DEVOPTS_DEBUGGING}.dumpLogs"
  const val KEY_DEVOPTS_DEBUGGING_ENABLE_LOGSENDER = "${KEY_DEVOPTS_DEBUGGING}.enableLogsender"

  const val KEY_DEVOPTS_MCP = "${KEY_DEVOPTS}.mcp"
  const val KEY_DEVOPTS_MCP_ENABLED = "${KEY_DEVOPTS_MCP}.enabled"
  const val KEY_DEVOPTS_MCP_HOST = "${KEY_DEVOPTS_MCP}.host"
  const val KEY_DEVOPTS_MCP_PORT = "${KEY_DEVOPTS_MCP}.port"
  const val KEY_DEVOPTS_MCP_TOKEN = "${KEY_DEVOPTS_MCP}.token"
  const val KEY_DEVOPTS_MCP_TOKEN_VISIBLE = "${KEY_DEVOPTS_MCP}.tokenVisible"
  const val KEY_DEVOPTS_MCP_ALLOW_REMOTE = "${KEY_DEVOPTS_MCP}.allowRemote"
  const val KEY_DEVOPTS_MCP_ALLOW_WRITE = "${KEY_DEVOPTS_MCP}.allowWrite"
  const val KEY_DEVOPTS_MCP_ALLOW_BUILD = "${KEY_DEVOPTS_MCP}.allowBuild"
  const val KEY_DEVOPTS_MCP_GENERATE_TOKEN = "${KEY_DEVOPTS_MCP}.generateToken"
  const val KEY_DEVOPTS_MCP_STATUS = "${KEY_DEVOPTS_MCP}.status"

  var dumpLogs: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_DEBUGGING_DUMPLOGS, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_DEBUGGING_DUMPLOGS, value)
    }

  var logsenderEnabled: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_DEBUGGING_ENABLE_LOGSENDER, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_DEBUGGING_ENABLE_LOGSENDER, value)
    }

  /** Whether the MCP server should be running. */
  var mcpServerEnabled: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_MCP_ENABLED, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_MCP_ENABLED, value)
    }

  /** Host the MCP server binds to when remote access is allowed. */
  var mcpHost: String
    get() = prefManager.getString(KEY_DEVOPTS_MCP_HOST, DEFAULT_MCP_HOST) ?: DEFAULT_MCP_HOST
    set(value) {
      prefManager.putString(KEY_DEVOPTS_MCP_HOST, value.trim())
    }

  /** Port the MCP server listens on. */
  var mcpPort: Int
    get() = prefManager.getInt(KEY_DEVOPTS_MCP_PORT, DEFAULT_MCP_PORT)
    set(value) {
      prefManager.putInt(KEY_DEVOPTS_MCP_PORT, value)
    }

  /** Bearer token required by the MCP server. Blank disables authentication. */
  var mcpToken: String
    get() = prefManager.getString(KEY_DEVOPTS_MCP_TOKEN, "") ?: ""
    set(value) {
      prefManager.putString(KEY_DEVOPTS_MCP_TOKEN, value.trim())
    }

  /** Whether the access token is shown as plain text instead of a masked field. */
  var mcpTokenVisible: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_MCP_TOKEN_VISIBLE, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_MCP_TOKEN_VISIBLE, value)
    }

  /** Whether the MCP server may be bound to a non-loopback address. */
  var mcpAllowRemote: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_MCP_ALLOW_REMOTE, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_MCP_ALLOW_REMOTE, value)
    }

  /** Whether the MCP server exposes the write tools. */
  var mcpAllowWrite: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_MCP_ALLOW_WRITE, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_MCP_ALLOW_WRITE, value)
    }

  /** Whether the MCP server exposes the build tools. */
  var mcpAllowBuild: Boolean
    get() = prefManager.getBoolean(KEY_DEVOPTS_MCP_ALLOW_BUILD, false)
    set(value) {
      prefManager.putBoolean(KEY_DEVOPTS_MCP_ALLOW_BUILD, value)
    }

  const val DEFAULT_MCP_HOST = "127.0.0.1"
  const val DEFAULT_MCP_PORT = 6688
}
