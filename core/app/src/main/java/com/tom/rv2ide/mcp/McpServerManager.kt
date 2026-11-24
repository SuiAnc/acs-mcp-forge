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

package com.tom.rv2ide.mcp

import android.content.Context
import com.tom.rv2ide.buildinfo.BuildInfo
import com.tom.rv2ide.mcp.protocol.McpProtocol
import com.tom.rv2ide.mcp.security.AuditLog
import com.tom.rv2ide.mcp.security.BearerAuth
import com.tom.rv2ide.mcp.server.HttpRequest
import com.tom.rv2ide.mcp.server.HttpResponse
import com.tom.rv2ide.mcp.server.McpHttpServer
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import java.io.File
import java.net.BindException
import java.security.SecureRandom
import org.slf4j.LoggerFactory

/**
 * Owns the lifecycle of the MCP server.
 *
 * The server follows the "MCP server" switch in `Settings > Developer options`. Every preference
 * change calls [applyPreferences], which stops a running server and starts a new one with the new
 * configuration.
 *
 * @author AndroidCodeStudio
 */
object McpServerManager {

  const val SERVER_NAME = "android-code-studio"

  const val DEFAULT_PORT = 6688

  private const val LOOPBACK = "127.0.0.1"

  private val log = LoggerFactory.getLogger(McpServerManager::class.java)

  private var appContext: Context? = null

  private var httpServer: McpHttpServer? = null
  private var facade: McpBuildFacade? = null
  private var registry: McpToolRegistry? = null
  private var protocol: McpProtocol? = null

  @Volatile private var boundHost: String = ""
  @Volatile private var boundPort: Int = -1
  @Volatile private var lastError: String? = null
  private var auditLog: AuditLog? = null

  /** Called once from [com.tom.rv2ide.app.IDEApplication.onCreate]. Never blocks the main thread. */
  fun init(context: Context) {
    appContext = context.applicationContext
    val thread =
        Thread({ runCatching { applyPreferences() } }, "mcp-server-init").apply { isDaemon = true }
    thread.start()
  }

  /** Applies the current preferences: starts, restarts or stops the server. */
  @Synchronized
  fun applyPreferences() {
    val context = appContext ?: return

    if (!DevOpsPreferences.mcpServerEnabled) {
      stop()
      return
    }

    start(context)
  }

  /** Stops and starts the server again. */
  fun restart() = applyPreferences()

  fun stop() {
    synchronized(this) {
      httpServer?.let { server ->
        log.info("Stopping MCP server")
        runCatching { server.stop() }
      }
      httpServer = null

      facade?.let { runCatching { it.unbind() } }
      facade = null
      registry = null
      protocol = null

      boundHost = ""
      boundPort = -1
    }
  }

  fun isRunning(): Boolean = httpServer?.isRunning == true

  fun address(): String = if (isRunning()) "$boundHost:$boundPort" else ""

  fun requestsServed(): Long = httpServer?.requestsServed ?: 0L

  fun errorMessage(): String? = lastError

  /** Returns the audit log of the tool invocations. */
  fun auditLog(): AuditLog? = auditLog

  /** Returns the last [lines] audit log entries. */
  fun recentAuditEntries(lines: Int = 50): List<String> = auditLog?.tail(lines) ?: emptyList()

  /** Clears the audit log. */
  fun clearAuditLog() {
    auditLog?.clear()
  }

  /** Human readable status shown in the preferences screen. */
  fun statusSummary(): String {
    val context = appContext
    if (context == null) return "Not initialized"

    if (!DevOpsPreferences.mcpServerEnabled) {
      return "Disabled"
    }

    if (!isRunning()) {
      return lastError ?: "Starting..."
    }

    val auth = if (DevOpsPreferences.mcpToken.isBlank()) "no token" else "token required"
    val write = if (DevOpsPreferences.mcpAllowWrite) "write enabled" else "read only"
    val build = if (DevOpsPreferences.mcpAllowBuild) "build enabled" else "build disabled"
    return "Listening on http://${address()}/mcp · $auth · $write · $build · " +
        "${requestsServed()} requests"
  }

  /** Generates a random bearer token for the settings screen. */
  fun generateToken(): String {
    val bytes = ByteArray(24)
    SecureRandom().nextBytes(bytes)
    val builder = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
      val value = byte.toInt() and 0xFF
      builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return builder.toString()
  }

  private fun start(context: Context) {
    stop()

    val allowRemote = DevOpsPreferences.mcpAllowRemote
    val host = if (allowRemote) DevOpsPreferences.mcpHost.ifBlank { "0.0.0.0" } else LOOPBACK
    val configuredPort = DevOpsPreferences.mcpPort
    val port = if (configuredPort in 1024..65535) configuredPort else DEFAULT_PORT

    val gate =
        McpProtocol.PermissionGate { permission ->
          when (permission) {
            com.tom.rv2ide.mcp.tools.McpPermission.READ -> true
            com.tom.rv2ide.mcp.tools.McpPermission.WRITE -> DevOpsPreferences.mcpAllowWrite
            com.tom.rv2ide.mcp.tools.McpPermission.BUILD -> DevOpsPreferences.mcpAllowBuild
          }
        }

    val audit = AuditLog(File(context.filesDir, "mcp/audit.log"))
    val buildFacade = McpBuildFacade(context) { message -> log.info("[mcp] {}", message) }
    val toolRegistry = McpToolRegistry(context, buildFacade)
    val mcpProtocol =
        McpProtocol(
            serverName = SERVER_NAME,
            serverVersion = serverVersion(),
            provider = toolRegistry,
            permissionGate = gate,
            logger = { message ->
              log.info("[mcp] {}", message)
              if (message.startsWith("tools/call")) {
                audit.append(message)
              }
            },
        )

    val server =
        McpHttpServer(host, port) { request -> dispatch(context, mcpProtocol, request) }

    try {
      server.start()
    } catch (e: BindException) {
      lastError =
          "Unable to bind $host:$port — the port is in use or not permitted. " +
              "Pick another port in the MCP settings."
      log.warn("Unable to start the MCP server", e)
      runCatching { buildFacade.unbind() }
      return
    } catch (e: Exception) {
      lastError = "Unable to start the MCP server: ${e.message ?: e::class.java.simpleName}"
      log.warn("Unable to start the MCP server", e)
      runCatching { buildFacade.unbind() }
      return
    }

    httpServer = server
    facade = buildFacade
    auditLog = audit
    registry = toolRegistry
    protocol = mcpProtocol
    boundHost = host
    boundPort = server.boundPort
    lastError = null

    log.info("MCP server listening on {}:{}", host, server.boundPort)
  }

  private fun dispatch(
      context: Context,
      mcpProtocol: McpProtocol,
      request: HttpRequest,
  ): HttpResponse {
    return try {
      if (!BearerAuth.isAuthorized(request, DevOpsPreferences.mcpToken)) {
        return HttpResponse.unauthorized()
      }
      mcpProtocol.handleHttp(request)
    } catch (e: Exception) {
      log.warn("Failed to handle the MCP request", e)
      HttpResponse.json(
          500,
          """{"error":"internal_error","message":"${e.message ?: "internal error"}"}""",
      )
    }
  }

  private fun serverVersion(): String {
    return runCatching { BuildInfo.VERSION_NAME_SIMPLE }.getOrDefault("0.0.0")
  }

  private const val HEX = "0123456789abcdef"
}
