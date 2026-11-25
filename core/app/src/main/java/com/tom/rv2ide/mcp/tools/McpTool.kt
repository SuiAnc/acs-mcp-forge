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

import com.tom.rv2ide.mcp.security.PathSandbox
import java.io.File
import kotlinx.serialization.json.JsonObject

/** Permission required by an [McpTool]. */
enum class McpPermission {
  /** Always available while the server is enabled. */
  READ,
  /** Requires the "allow write operations" switch. */
  WRITE,
  /** Requires the "allow builds" switch. */
  BUILD,
  /** Requires the "allow network operations" switch (git fetch/pull/push). */
  NETWORK,
}

/** Result of a tool invocation. */
class McpToolResult private constructor(val text: String, val isError: Boolean) {

  companion object {

    fun success(text: String): McpToolResult = McpToolResult(text, false)

    fun failure(message: String): McpToolResult = McpToolResult(message, true)
  }
}

/** A tool exposed over MCP. */
interface McpTool {

  /** Unique `snake_case` name of the tool. */
  val name: String

  /** Human readable description, shown to the client. */
  val description: String

  /** Permission required to invoke this tool. */
  val permission: McpPermission

  /** JSON schema of the arguments accepted by this tool. */
  val inputSchema: JsonObject

  /** Executes the tool. Implementations must not throw for expected failures. */
  fun call(arguments: JsonObject, context: McpToolContext): McpToolResult
}

/** Notifies the IDE when files are changed so that editors and the file tree stay in sync. */
interface FileEventSink {

  fun onFileCreated(file: File)

  fun onFileDeleted(file: File)

  fun onFileRenamed(source: File, destination: File)

  fun onFileChanged(file: File)

  /** Sink used when the IDE is not available (for example in the JVM self check). */
  object NoOp : FileEventSink {

    override fun onFileCreated(file: File) {}

    override fun onFileDeleted(file: File) {}

    override fun onFileRenamed(source: File, destination: File) {}

    override fun onFileChanged(file: File) {}
  }
}

/** Everything a tool needs while it is executed. */
interface McpToolContext {

  /** Sandbox which restricts every filesystem access. */
  val sandbox: PathSandbox

  /** Root directory of the project that is currently opened in the IDE, if any. */
  val projectRoot: File?

  /** Directory where deleted files are moved to (`null` disables deletion). */
  val trashDir: File?

  /** Sink used to notify the IDE about file system changes. */
  val fileEvents: FileEventSink

  /** Maximum number of bytes returned by `file_read`. */
  val maxReadBytes: Long

  /** Returns the in-memory content of an opened editor document, or `null` if it is not open. */
  fun openDocumentContent(file: File): String?
}

/**
 * Default [McpToolContext] implementation.
 *
 * @author AndroidCodeStudio
 */
class DefaultMcpToolContext(
    override val sandbox: PathSandbox,
    override val projectRoot: File? = null,
    override val trashDir: File? = null,
    override val fileEvents: FileEventSink = FileEventSink.NoOp,
    override val maxReadBytes: Long = 2L * 1024L * 1024L,
    private val documentContentProvider: (File) -> String? = { null },
) : McpToolContext {

  override fun openDocumentContent(file: File): String? = documentContentProvider(file)
}
