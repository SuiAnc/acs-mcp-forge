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

package com.tom.rv2ide.mcp.devcheck

import com.tom.rv2ide.mcp.protocol.JsonRpc
import com.tom.rv2ide.mcp.protocol.McpProtocol
import com.tom.rv2ide.mcp.security.AuditLog
import com.tom.rv2ide.mcp.security.BearerAuth
import com.tom.rv2ide.mcp.security.PathSandbox
import com.tom.rv2ide.mcp.security.SandboxViolationException
import com.tom.rv2ide.mcp.server.HttpRequest
import com.tom.rv2ide.mcp.server.McpHttpServer
import com.tom.rv2ide.mcp.tools.DefaultMcpToolContext
import com.tom.rv2ide.mcp.tools.FileEventSink
import com.tom.rv2ide.mcp.tools.FileTools
import com.tom.rv2ide.mcp.tools.McpPermission
import com.tom.rv2ide.mcp.tools.McpTool
import com.tom.rv2ide.mcp.tools.McpToolContext
import com.tom.rv2ide.mcp.tools.TextEdit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JVM self check for the Android independent part of the MCP implementation.
 *
 * It starts the real [McpHttpServer] with the real [McpProtocol] and the real file tools, then talks
 * to it over HTTP. Run it with the Kotlin command line compiler:
 *
 * ```
 * kotlinc -cp kotlinx-serialization-json.jar:kotlinx-serialization-core.jar \
 *     -d out <sources...> docs/mcp/devcheck/JvmSelfCheck.kt
 * kotlin -cp out:kotlinx-serialization-json.jar:kotlinx-serialization-core.jar \
 *     com.tom.rv2ide.mcp.devcheck.JvmSelfCheckKt
 * ```
 */
fun main() {
  var passed = 0
  var failed = 0

  fun check(name: String, condition: Boolean, details: String = "") {
    if (condition) {
      passed++
      println("PASS  $name")
    } else {
      failed++
      println("FAIL  $name ${if (details.isNotEmpty()) "-> $details" else ""}")
    }
  }

  val root = File(System.getProperty("java.io.tmpdir"), "mcp-devcheck-" + System.nanoTime())
  root.mkdirs()
  val trash = File(root, ".trash")
  File(root, "app/src/main").mkdirs()
  File(root, "app/src/main/Main.kt").writeText("fun main() {\n  println(\"hello\")\n}\n")
  File(root, "README.md").writeText("# demo project\n")

  val sandbox = PathSandbox(listOf(root))
  var created = 0
  var deleted = 0
  var renamed = 0
  val events =
      object : FileEventSink {
        override fun onFileCreated(file: File) {
          created++
        }

        override fun onFileDeleted(file: File) {
          deleted++
        }

        override fun onFileRenamed(source: File, destination: File) {
          renamed++
        }

        override fun onFileChanged(file: File) {}
      }
  val toolContext: McpToolContext =
      DefaultMcpToolContext(
          sandbox = sandbox,
          projectRoot = root,
          trashDir = trash,
          fileEvents = events,
      )

  val tools: List<McpTool> = FileTools.all()
  val provider =
      object : McpProtocol.ToolProvider {
        override fun availableTools(): List<McpTool> = tools

        override fun contextFor(tool: McpTool): McpToolContext = toolContext
      }

  val protocol =
      McpProtocol(
          serverName = "android-code-studio",
          serverVersion = "devcheck",
          provider = provider,
          permissionGate = McpProtocol.PermissionGate { it != McpPermission.BUILD },
      )

  val server = McpHttpServer("127.0.0.1", 0, protocol::handleHttp)
  server.start()
  val base = "http://127.0.0.1:${server.boundPort}"
  println("MCP self check server listening on $base")
  println("workspace: ${root.path}\n")

  try {
    // --- transport -------------------------------------------------------------------------
    val health = httpGet("$base/health")
    check("GET /health returns 200", health.first == 200, "status=${health.first}")
    check("GET /health reports ok", health.second.contains("\"status\":\"ok\""), health.second)

    val notFound = httpGet("$base/nothing")
    check("unknown endpoint returns 404", notFound.first == 404, "status=${notFound.first}")
    check(
        "404 response hints at the MCP endpoint",
        notFound.second.contains("/mcp"),
        notFound.second,
    )

    val rootHealth = httpGet("$base/")
    check("GET / reports health", rootHealth.first == 200 && rootHealth.second.contains("\"status\":\"ok\""), rootHealth.second)

    val rootInitialize =
        rpcUrl("$base/", """{"jsonrpc":"2.0","id":99,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
    check("POST / works like POST /mcp", rootInitialize.contains("\"serverInfo\""), rootInitialize)

    val sseResponse =
        postWithAccept(
            "$base/mcp",
            """{"jsonrpc":"2.0","id":98,"method":"ping"}""",
            "text/event-stream",
        )
    check(
        "SSE only clients receive an event stream",
        sseResponse.second.contains("data:") && sseResponse.second.contains("\"result\""),
        sseResponse.second,
    )

    // --- protocol --------------------------------------------------------------------------
    val initialize = rpc(base, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"devcheck","version":"1"}}}""")
    check("initialize succeeds", initialize.contains("\"serverInfo\""), initialize)
    check("initialize negotiates the protocol version", initialize.contains("2024-11-05"), initialize)

    val ping = rpc(base, """{"jsonrpc":"2.0","id":2,"method":"ping"}""")
    check("ping returns an empty result", ping.contains("\"result\":{}"), ping)

    val initializedNotification = rpc(base, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    check("initialized notification is accepted", initializedNotification.isEmpty(), initializedNotification)

    val toolsList = rpc(base, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")
    check("tools/list contains file_read", toolsList.contains("\"file_read\""), toolsList)
    check("tools/list hides build tools (permission gate)", !toolsList.contains("build_start"), "")

    val unknownMethod = rpc(base, """{"jsonrpc":"2.0","id":4,"method":"does/not/exist"}""")
    check("unknown method returns -32601", unknownMethod.contains("-32601"), unknownMethod)

    val badJson = rpc(base, """{"jsonrpc":""")
    check("malformed JSON returns -32700", badJson.contains("-32700"), badJson)

    // --- read tools ------------------------------------------------------------------------
    val list = rpc(base, toolsCall(10, "file_list", """{"path":"."}"""))
    check("file_list lists the workspace", list.contains("README.md"), list)

    val tree = rpc(base, toolsCall(11, "file_tree", """{"maxDepth":4}"""))
    check("file_tree walks nested directories", tree.contains("Main.kt"), tree)

    val read = rpc(base, toolsCall(12, "file_read", """{"path":"README.md"}"""))
    check("file_read returns the content", read.contains("demo project"), read)

    val search = rpc(base, toolsCall(13, "file_search", """{"query":"hello"}"""))
    check("file_search finds text", search.contains("Main.kt"), search)

    val rangeRead = rpc(base, toolsCall(14, "file_read", """{"path":"app/src/main/Main.kt","startLine":2,"endLine":2}"""))
    check("file_read supports line ranges", rangeRead.contains("println"), rangeRead)

    // --- write tools -----------------------------------------------------------------------
    val created1 = rpc(base, toolsCall(20, "file_create", """{"path":"app/src/main/New.kt","content":"val x = 1\nval x = 2\n"}"""))
    check(
        "file_create writes a new file",
        !toolIsError(created1) && toolText(created1).contains("New.kt") &&
            File(root, "app/src/main/New.kt").exists(),
        created1,
    )
    check("file_create emits a file event", created == 1, "created=$created")

    val duplicate = rpc(base, toolsCall(21, "file_create", """{"path":"app/src/main/New.kt","content":""}"""))
    check("file_create refuses to overwrite without a flag", toolIsError(duplicate), duplicate)

    val edit = rpc(base, toolsCall(22, "file_edit", """{"path":"app/src/main/New.kt","oldText":"val x = 1","newText":"val x = 42"}"""))
    check(
        "file_edit replaces a unique snippet",
        !toolIsError(edit) && File(root, "app/src/main/New.kt").readText().contains("42"),
        edit,
    )

    val ambiguous = rpc(base, toolsCall(23, "file_edit", """{"path":"app/src/main/New.kt","oldText":"val x","newText":"val y"}"""))
    check("file_edit rejects ambiguous snippets", toolIsError(ambiguous), ambiguous)

    val missing = rpc(base, toolsCall(24, "file_edit", """{"path":"app/src/main/New.kt","oldText":"not-there","newText":"y"}"""))
    check("file_edit reports missing snippets", toolIsError(missing), missing)

    val write = rpc(base, toolsCall(25, "file_write", """{"path":"app/src/main/New.kt","content":"object New\n"}"""))
    check(
        "file_write overwrites and backs up",
        !toolIsError(write) &&
            toolText(write).contains("backup") &&
            File(root, "app/src/main/New.kt").readText() == "object New\n",
        write,
    )

    val rename = rpc(base, toolsCall(26, "file_rename", """{"path":"app/src/main/New.kt","newName":"Renamed.kt"}"""))
    check("file_rename renames the file", File(root, "app/src/main/Renamed.kt").exists() && rename.contains("Renamed.kt"), rename)
    check("file_rename emits a file event", renamed == 1, "renamed=$renamed")

    File(root, "app/src/main/moved").mkdirs()
    val move = rpc(base, toolsCall(27, "file_move", """{"source":"app/src/main/Renamed.kt","destination":"app/src/main/moved"}"""))
    check("file_move moves into a directory", File(root, "app/src/main/moved/Renamed.kt").exists(), move)

    val delete = rpc(base, toolsCall(28, "file_delete", """{"path":"app/src/main/moved/Renamed.kt"}"""))
    check(
        "file_delete moves the file to the trash",
        !File(root, "app/src/main/moved/Renamed.kt").exists() &&
            toolText(delete).contains("trashedTo"),
        delete,
    )

    File(root, "app/src/main/moved/tmp.txt").writeText("x")
    val purge = rpc(base, toolsCall(29, "file_delete", """{"path":"app/src/main/moved","recursive":true,"purge":true}"""))
    check(
        "file_delete purges recursively",
        !File(root, "app/src/main/moved").exists() && toolText(purge).contains("\"purged\":true"),
        purge,
    )

    // --- sandbox ---------------------------------------------------------------------------
    val escape = rpc(base, toolsCall(30, "file_read", """{"path":"/etc/passwd"}"""))
    check("file_read rejects paths outside the sandbox", toolIsError(escape), escape)

    val traversal = rpc(base, toolsCall(31, "file_read", """{"path":"../../../../etc/hosts"}"""))
    check("file_read rejects ../ traversal", toolIsError(traversal), traversal)

    val rootDelete = rpc(base, toolsCall(32, "file_delete", """{"path":".","recursive":true}"""))
    check("file_delete refuses to delete the sandbox root", toolIsError(rootDelete), rootDelete)

    val unknownTool = rpc(base, toolsCall(33, "no_such_tool", "{}"))
    check("unknown tool returns -32602", unknownTool.contains("-32602"), unknownTool)
    check("sandbox root still exists", root.exists(), "")

    // --- unit level checks -----------------------------------------------------------------
    check(
        "PathSandbox allows nested paths",
        runCatching { sandbox.resolve("app/src/main/Main.kt", mustExist = true) }.isSuccess,
    )
    check(
        "PathSandbox rejects absolute system paths",
        runCatching { sandbox.resolve("/system/etc/hosts") }.exceptionOrNull()
            is SandboxViolationException,
    )
    check(
        "TextEdit counts occurrences",
        TextEdit.countOccurrences("aaaa", "aa") == 2,
        "count=${TextEdit.countOccurrences("aaaa", "aa")}",
    )
    val bearer =
        HttpRequest(
            "POST",
            "/mcp",
            emptyMap(),
            mapOf("authorization" to "Bearer secret-token"),
            ByteArray(0),
        )
    check("BearerAuth accepts the configured token", BearerAuth.isAuthorized(bearer, "secret-token"))
    check(
        "BearerAuth rejects a wrong token",
        !BearerAuth.isAuthorized(bearer, "other-token"),
    )
    check("BearerAuth accepts everything when no token is set", BearerAuth.isAuthorized(bearer, ""))

    // --- regression checks for the reported defects -----------------------------------------
    File(root, "lines4.txt").writeText("alpha\nbeta\ngamma\ndelta\n")
    val lines4 = rpc(base, toolsCall(40, "file_read", """{"path":"lines4.txt"}"""))
    check(
        "file_read counts lines without the trailing newline",
        toolText(lines4).contains("\"totalLines\":4"),
        toolText(lines4),
    )

    val binaryBytes =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x01, 0x02)
    File(root, "pic.png").writeBytes(binaryBytes)
    val binaryRead = rpc(base, toolsCall(41, "file_read", """{"path":"pic.png"}"""))
    check(
        "file_read reports binary files",
        toolText(binaryRead).contains("\"isBinary\":true") &&
            toolText(binaryRead).contains("hexPreview"),
        toolText(binaryRead),
    )

    File(root, "build/intermediates").mkdirs()
    File(root, "build/intermediates/x.txt").writeText("x")
    val filtered = rpc(base, toolsCall(42, "file_list", """{"path":"."}"""))
    check(
        "file_list hides build/ by default",
        !toolText(filtered).contains("\"name\":\"build\""),
        toolText(filtered),
    )
    val unfiltered = rpc(base, toolsCall(43, "file_list", """{"path":".","includeIgnored":true}"""))
    check(
        "file_list can include build/ on demand",
        toolText(unfiltered).contains("\"name\":\"build\""),
        toolText(unfiltered),
    )

    val deepTree = rpc(base, toolsCall(44, "file_tree", """{"path":".","maxDepth":2}"""))
    check(
        "file_tree marks truncated directories",
        toolText(deepTree).contains("\"truncated\""),
        toolText(deepTree).substring(0, minOf(400, toolText(deepTree).length)),
    )

    val searchEcho = rpc(base, toolsCall(45, "file_search", """{"query":"alpha"}"""))
    check(
        "file_search echoes its effective options",
        toolText(searchEcho).contains("\"caseSensitive\":false") &&
            toolText(searchEcho).contains("\"regex\":false"),
        toolText(searchEcho),
    )

    val notificationOnly = protocol.handlePayload("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    check("notification only payloads produce no response", notificationOnly == null, "")

    // --- audit log -------------------------------------------------------------------------
    val audit = AuditLog(File(root, "audit.log"))
    audit.append("tools/call name=file_read durationMs=3 isError=false")
    audit.append("tools/call name=file_list durationMs=1 isError=false")
    check("AuditLog records entries", audit.tail(10).size == 2, "size=${audit.tail(10).size}")
    check(
        "AuditLog keeps the newest entries last",
        audit.tail(1).firstOrNull()?.contains("file_list") == true,
        audit.tail(1).toString(),
    )
    check("AuditLog reports its size", audit.size() > 0, "size=${audit.size()}")
    audit.clear()
    check("AuditLog can be cleared", audit.tail(10).isEmpty())
  } finally {
    server.stop()
    root.deleteRecursively()
  }

  println("\n$passed passed, $failed failed")
  if (failed > 0) {
    exitProcess(1)
  }
}

/** Extracts the text payload of a `tools/call` response. */
private fun toolText(response: String): String {
  return runCatching {
        val result = JsonRpc.parseAny(response).jsonObject["result"]?.jsonObject ?: return ""
        result["content"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.content
            ?: ""
      }
      .getOrDefault("")
}

/** Returns the `isError` flag of a `tools/call` response. */
private fun toolIsError(response: String): Boolean {
  return runCatching {
        JsonRpc.parseAny(response).jsonObject["result"]?.jsonObject?.get("isError")?.jsonPrimitive
            ?.content == "true"
      }
      .getOrDefault(false)
}

private fun toolsCall(id: Int, name: String, arguments: String): String {
  return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
}

private fun rpcUrl(url: String, payload: String): String {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "POST"
  connection.doOutput = true
  connection.setRequestProperty("Content-Type", "application/json")
  connection.setRequestProperty("Accept", "application/json, text/event-stream")
  connection.connectTimeout = 10_000
  connection.readTimeout = 60_000
  connection.outputStream.use { it.write(payload.toByteArray()) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  return stream?.bufferedReader()?.use { it.readText() } ?: ""
}

private fun postWithAccept(url: String, payload: String, accept: String): Pair<Int, String> {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "POST"
  connection.doOutput = true
  connection.setRequestProperty("Content-Type", "application/json")
  connection.setRequestProperty("Accept", accept)
  connection.connectTimeout = 10_000
  connection.readTimeout = 60_000
  connection.outputStream.use { it.write(payload.toByteArray()) }
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
  return status to body
}

private fun rpc(baseUrl: String, payload: String): String {
  val connection = URL("$baseUrl/mcp").openConnection() as HttpURLConnection
  connection.requestMethod = "POST"
  connection.doOutput = true
  connection.setRequestProperty("Content-Type", "application/json")
  connection.setRequestProperty("Accept", "application/json, text/event-stream")
  connection.connectTimeout = 10_000
  connection.readTimeout = 60_000
  connection.outputStream.use { it.write(payload.toByteArray()) }

  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  if (stream == null) return ""
  return stream.bufferedReader().use { it.readText() }
}

private fun httpGet(url: String): Pair<Int, String> {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "GET"
  connection.connectTimeout = 10_000
  connection.readTimeout = 10_000
  val status = connection.responseCode
  val stream = if (status in 200..299) connection.inputStream else connection.errorStream
  val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
  return status to body
}

@Suppress("unused")
private fun JsonObject.field(name: String): String? =
    runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

@Suppress("unused")
private fun String.asJson(): JsonObject = JsonRpc.parseAny(this).jsonObject
