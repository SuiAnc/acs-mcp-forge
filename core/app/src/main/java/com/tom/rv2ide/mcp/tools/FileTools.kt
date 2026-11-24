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

import com.tom.rv2ide.mcp.protocol.Args
import com.tom.rv2ide.mcp.protocol.McpArgumentException
import com.tom.rv2ide.mcp.security.SandboxViolationException
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Filesystem tools exposed over MCP.
 *
 * All paths are resolved through the [com.tom.rv2ide.mcp.security.PathSandbox] of the current tool
 * context. Read-only tools work whenever the server is enabled, write tools additionally require
 * the "allow write operations" switch.
 *
 * @author AndroidCodeStudio
 */
object FileTools {

  /** Maximum number of files visited by a single recursive operation. */
  private const val MAX_WALK_ENTRIES = 20000

  /** Directories which are skipped by recursive listings and searches. */
  val IGNORED_DIRECTORIES =
      setOf(".git", ".gradle", "build", "node_modules", ".idea", ".cxx", "__pycache__", ".kotlin")

  fun all(): List<McpTool> =
      listOf(
          FileListTool,
          FileTreeTool,
          FileReadTool,
          FileSearchTool,
          FileCreateTool,
          FileCreateDirTool,
          FileWriteTool,
          FileEditTool,
          FileRenameTool,
          FileMoveTool,
          FileDeleteTool,
      )

  // ---------------------------------------------------------------------------------------------
  // Read-only tools
  // ---------------------------------------------------------------------------------------------

  object FileListTool : McpTool {

    override val name = "file_list"

    override val description =
        "List the entries of a directory. Relative paths are resolved against the project root."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("Directory to list. Defaults to the opened project root."),
                "recursive" to Schema.boolean("Walk the tree recursively.", false),
                "maxDepth" to Schema.integer("Maximum depth for recursive listings.", 3),
                "includeIgnored" to
                    Schema.boolean("Include .git/build/node_modules directories.", false),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val root = ToolSupport.resolvePath(arguments, context, required = false)
        if (!root.exists()) return McpToolResult.failure("No such directory: ${root.path}")
        if (!root.isDirectory) {
          return McpToolResult.failure("Not a directory: ${root.path}")
        }

        val recursive = Args.boolean(arguments, "recursive", false)
        val maxDepth = Args.integer(arguments, "maxDepth", 3).coerceIn(1, 12)
        val includeIgnored = Args.boolean(arguments, "includeIgnored", false)

        val entries = ArrayList<File>()
        if (recursive) {
          ToolSupport.walk(root, maxDepth, includeIgnored) { entries.add(it) }
        } else {
          ToolSupport.filterEntries(root, includeIgnored).forEach { entries.add(it) }
        }

        val body =
            buildJsonObject {
              put("path", root.path)
              put("recursive", recursive)
              put("count", entries.size)
              put(
                  "entries",
                  buildJsonArray {
                    entries.forEach { add(ToolSupport.fileJson(it)) }
                  },
              )
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileTreeTool : McpTool {

    override val name = "file_tree"

    override val description = "Show a nested view of the project tree."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("Root of the tree. Defaults to the opened project root."),
                "maxDepth" to Schema.integer("Maximum depth of the tree.", 4),
                "includeIgnored" to
                    Schema.boolean("Include .git/build/node_modules directories.", false),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val root = ToolSupport.resolvePath(arguments, context, required = false)
        if (!root.isDirectory) {
          return McpToolResult.failure("Not a directory: ${root.path}")
        }
        val maxDepth = Args.integer(arguments, "maxDepth", 4).coerceIn(1, 8)
        val includeIgnored = Args.boolean(arguments, "includeIgnored", false)

        val body =
            buildJsonObject {
              put("path", root.path)
              put("tree", ToolSupport.treeJson(root, maxDepth, includeIgnored, 0))
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileReadTool : McpTool {

    override val name = "file_read"

    override val description =
        "Read a text file. Returns the content of the unsaved editor buffer when the file is open."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("File to read."),
                "startLine" to Schema.integer("First line to return (1-based).", 1),
                "endLine" to Schema.integer("Last line to return (inclusive)."),
            ),
            required = listOf("path"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val file = ToolSupport.resolvePath(arguments, context, required = true)
        if (!file.exists()) return McpToolResult.failure("No such file: ${file.path}")
        if (file.isDirectory) return McpToolResult.failure("Not a file: ${file.path}")

        val fromEditor = context.openDocumentContent(file)
        if (fromEditor == null && ToolSupport.looksBinary(file)) {
          return McpToolResult.success(ToolSupport.binaryFileJson(file))
        }

        val content = fromEditor ?: file.readText()

        if (content.length.toLong() > context.maxReadBytes) {
          return McpToolResult.failure(
              "File is too large to read (${content.length} chars, limit ${context.maxReadBytes}). " +
                  "Use startLine/endLine to read a range."
          )
        }

        val lines = content.split('\n')
        val hasTrailingNewline = content.endsWith("\n")
        val totalLines = ToolSupport.countLines(content)
        val start = Args.integer(arguments, "startLine", 1).coerceIn(1, maxOf(totalLines, 1))
        val requestedEnd = Args.integer(arguments, "endLine", totalLines)
        val end = requestedEnd.coerceIn(start, maxOf(totalLines, 1))

        val selected = lines.subList(start - 1, end)
        var text = selected.joinToString("\n")
        if (text.length.toLong() > context.maxReadBytes) {
          text = text.substring(0, context.maxReadBytes.toInt())
        }

        val body =
            buildJsonObject {
              put("path", file.path)
              put("totalLines", totalLines)
              put("startLine", start)
              put("endLine", end)
              put("fromEditor", fromEditor != null)
              put("isBinary", false)
              put("hasTrailingNewline", hasTrailingNewline)
              put("content", text)
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileSearchTool : McpTool {

    override val name = "file_search"

    override val description = "Search for text or a regular expression inside project files."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "query" to Schema.string("Text or regular expression to search for."),
                "path" to Schema.string("Directory to search. Defaults to the project root."),
                "glob" to Schema.string("Only search files whose name ends with this suffix."),
                "regex" to Schema.boolean("Treat the query as a regular expression.", false),
                "caseSensitive" to Schema.boolean("Case sensitive search.", false),
                "maxResults" to Schema.integer("Maximum number of matches to return.", 50),
            ),
            required = listOf("query"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val query = Args.string(arguments, "query")
        if (query.isEmpty()) throw McpArgumentException("Argument 'query' must not be empty")

        val root = ToolSupport.resolvePath(arguments, context, required = false)
        if (!root.isDirectory) return McpToolResult.failure("Not a directory: ${root.path}")

        val glob = Args.optionalString(arguments, "glob")
        val useRegex = Args.boolean(arguments, "regex", false)
        val caseSensitive = Args.boolean(arguments, "caseSensitive", false)
        val maxResults = Args.integer(arguments, "maxResults", 50).coerceIn(1, 500)

        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern =
            if (useRegex) {
              runCatching { Regex(query, options) }
                  .getOrElse {
                    return McpToolResult.failure("Invalid regular expression: ${it.message}")
                  }
            } else {
              Regex(Regex.escape(query), options)
            }

        val matches = ArrayList<JsonObject>()
        var truncated = false

        ToolSupport.walk(root, maxDepth = 12, includeIgnored = false) { file ->
          if (matches.size >= maxResults || truncated) return@walk
          if (file.isDirectory) {
            return@walk
          }
          if (glob != null && !file.name.endsWith(glob)) {
            return@walk
          }
          if (file.length() > 1024L * 1024L) {
            return@walk
          }
          if (ToolSupport.isProbablyBinary(file)) {
            return@walk
          }

          val lines = runCatching { file.readLines() }.getOrNull() ?: return@walk
          for ((index, line) in lines.withIndex()) {
            if (matches.size >= maxResults) {
              truncated = true
              return@walk
            }
            val match = pattern.find(line) ?: continue
            matches.add(
                buildJsonObject {
                  put("path", file.path)
                  put("line", index + 1)
                  put("column", match.range.first + 1)
                  put("preview", line.trim().take(240))
                },
            )
          }
        }

        val body =
            buildJsonObject {
              put("query", query)
              put("path", root.path)
              put("regex", useRegex)
              put("caseSensitive", caseSensitive)
              glob?.let { put("glob", it) }
              put("count", matches.size)
              put("truncated", truncated)
              put("matches", buildJsonArray { matches.forEach { add(it) } })
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Write tools
  // ---------------------------------------------------------------------------------------------

  object FileCreateTool : McpTool {

    override val name = "file_create"

    override val description = "Create a new file, including any missing parent directories."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("Path of the new file."),
                "content" to Schema.string("Initial content of the file."),
                "overwrite" to Schema.boolean("Overwrite the file if it already exists.", false),
            ),
            required = listOf("path"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val file = ToolSupport.resolvePath(arguments, context, required = true)
        if (file.exists() && file.isDirectory) {
          return McpToolResult.failure("A directory exists at ${file.path}")
        }
        val overwrite = Args.boolean(arguments, "overwrite", false)
        if (file.exists() && !overwrite) {
          return McpToolResult.failure(
              "File already exists: ${file.path}. Pass overwrite=true to replace it."
          )
        }
        val content = Args.optionalString(arguments, "content") ?: ""

        file.parentFile?.mkdirs()
        file.writeText(content)
        context.fileEvents.onFileCreated(file)

        McpToolResult.success(
            """{"created":"${ToolSupport.escape(file.path)}","bytes":${content.toByteArray().size}}"""
        )
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileCreateDirTool : McpTool {

    override val name = "file_create_dir"

    override val description = "Create a new directory, including any missing parent directories."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(listOf("path" to Schema.string("Path of the new directory.")), listOf("path"))

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val dir = ToolSupport.resolvePath(arguments, context, required = true)
        if (dir.exists() && dir.isFile) {
          return McpToolResult.failure("A file exists at ${dir.path}")
        }
        val created = dir.mkdirs() || dir.isDirectory
        if (!created) {
          return McpToolResult.failure("Unable to create directory: ${dir.path}")
        }
        context.fileEvents.onFileCreated(dir)
        McpToolResult.success("""{"created":"${ToolSupport.escape(dir.path)}","isDirectory":true}""")
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileWriteTool : McpTool {

    override val name = "file_write"

    override val description = "Overwrite a file with the given content, keeping a backup by default."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("File to write."),
                "content" to Schema.string("New content of the file."),
                "createBackup" to Schema.boolean("Back up the previous content.", true),
            ),
            required = listOf("path", "content"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val file = ToolSupport.resolvePath(arguments, context, required = true)
        if (file.isDirectory) return McpToolResult.failure("Not a file: ${file.path}")
        val content = Args.string(arguments, "content")
        val createBackup = Args.boolean(arguments, "createBackup", true)

        var backup: String? = null
        if (createBackup && file.exists()) {
          backup = ToolSupport.backup(file, context, "write")
        }

        file.parentFile?.mkdirs()
        file.writeText(content)
        context.fileEvents.onFileChanged(file)

        val body =
            buildJsonObject {
              put("path", file.path)
              put("bytes", content.toByteArray().size)
              backup?.let { put("backup", it) }
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileEditTool : McpTool {

    override val name = "file_edit"

    override val description =
        "Replace an exact snippet in a file. Fails when the snippet is missing or ambiguous."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("File to edit."),
                "oldText" to Schema.string("Exact text to replace."),
                "newText" to Schema.string("Replacement text."),
                "replaceAll" to Schema.boolean("Replace every occurrence.", false),
            ),
            required = listOf("path", "oldText", "newText"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val file = ToolSupport.resolvePath(arguments, context, required = true)
        if (!file.exists()) return McpToolResult.failure("No such file: ${file.path}")
        if (file.isDirectory) return McpToolResult.failure("Not a file: ${file.path}")

        val oldText = Args.string(arguments, "oldText")
        val newText = Args.string(arguments, "newText")
        val replaceAll = Args.boolean(arguments, "replaceAll", false)

        val original = context.openDocumentContent(file) ?: file.readText()
        val result = TextEdit.replace(original, oldText, newText, replaceAll)
        if (result is TextEdit.Result.Failure) {
          return McpToolResult.failure(result.reason)
        }

        val success = result as TextEdit.Result.Success
        val backup = ToolSupport.backup(file, context, "edit")
        file.writeText(success.content)
        context.fileEvents.onFileChanged(file)

        val body =
            buildJsonObject {
              put("path", file.path)
              put("replaced", success.occurrences)
              backup?.let { put("backup", it) }
            }
        McpToolResult.success(body.toString())
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileRenameTool : McpTool {

    override val name = "file_rename"

    override val description = "Rename a file or directory inside its parent directory."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("File or directory to rename."),
                "newName" to Schema.string("New name (without any path separator)."),
            ),
            required = listOf("path", "newName"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val source = ToolSupport.resolvePath(arguments, context, required = true, mustExist = true)
        val newName = Args.string(arguments, "newName").trim()
        if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
          throw McpArgumentException("Argument 'newName' must be a plain file name")
        }

        val parent =
            source.parentFile ?: return McpToolResult.failure("Cannot rename a filesystem root")
        val target = File(parent, newName)
        if (!context.sandbox.contains(target)) {
          return McpToolResult.failure("Target is outside of the allowed directories")
        }
        if (target.exists()) {
          return McpToolResult.failure("Target already exists: ${target.path}")
        }
        if (!source.renameTo(target)) {
          return McpToolResult.failure("Unable to rename ${source.path} to ${target.path}")
        }

        context.fileEvents.onFileRenamed(source, target)
        McpToolResult.success(
            buildJsonObject {
                  put("from", source.path)
                  put("to", target.path)
                }
                .toString()
        )
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileMoveTool : McpTool {

    override val name = "file_move"

    override val description =
        "Move a file or directory. When 'destination' is an existing directory the entry is moved " +
            "into it."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "source" to Schema.string("File or directory to move."),
                "destination" to Schema.string("Target directory or target path."),
                "overwrite" to Schema.boolean("Replace an existing target.", false),
            ),
            required = listOf("source", "destination"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val source =
            ToolSupport.resolvePath(arguments, context, "source", required = true, mustExist = true)
        val rawDestination = Args.string(arguments, "destination")
        val destinationCandidate = context.sandbox.resolve(rawDestination)
        val target =
            if (destinationCandidate.isDirectory) {
              context.sandbox.resolve(File(destinationCandidate, source.name).path)
            } else {
              destinationCandidate
            }

        if (target.path == source.path) {
          return McpToolResult.failure("Source and destination are the same path")
        }
        if (ToolSupport.isAncestor(source, target)) {
          return McpToolResult.failure("Cannot move a directory into itself")
        }

        val overwrite = Args.boolean(arguments, "overwrite", false)
        if (target.exists() && !overwrite) {
          return McpToolResult.failure(
              "Target already exists: ${target.path}. Pass overwrite=true to replace it."
          )
        }
        if (target.exists() && overwrite && target.isDirectory) {
          if (!target.deleteRecursively()) {
            return McpToolResult.failure("Unable to replace directory: ${target.path}")
          }
        }

        target.parentFile?.mkdirs()

        val moved =
            source.renameTo(target) ||
                runCatching {
                  if (source.isDirectory) {
                    source.copyRecursively(target, overwrite = true)
                    source.deleteRecursively()
                  } else {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                  }
                  true
                }
                    .getOrDefault(false)

        if (!moved) {
          return McpToolResult.failure("Unable to move ${source.path} to ${target.path}")
        }

        context.fileEvents.onFileRenamed(source, target)
        McpToolResult.success(
            buildJsonObject {
                  put("from", source.path)
                  put("to", target.path)
                  put("overwritten", overwrite)
                }
                .toString()
        )
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  object FileDeleteTool : McpTool {

    override val name = "file_delete"

    override val description =
        "Delete a file or directory. Deleted entries are moved to the MCP trash directory unless " +
            "purge=true is passed."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "path" to Schema.string("File or directory to delete."),
                "recursive" to Schema.boolean("Required for non-empty directories.", false),
                "purge" to Schema.boolean("Delete permanently instead of moving to the trash.", false),
            ),
            required = listOf("path"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return runCatching {
        val target = ToolSupport.resolvePath(arguments, context, required = true, mustExist = true)
        if (context.sandbox.rootOf(target)?.path == target.path) {
          return McpToolResult.failure("Refusing to delete a sandbox root directory")
        }

        val recursive = Args.boolean(arguments, "recursive", false)
        val purge = Args.boolean(arguments, "purge", false)

        if (target.isDirectory && !recursive && (target.list()?.isNotEmpty() == true)) {
          return McpToolResult.failure(
              "Directory is not empty: ${target.path}. Pass recursive=true to delete it."
          )
        }

        val trash = context.trashDir
        if (!purge && trash != null) {
          val destination = ToolSupport.trashTarget(trash, context.sandbox, target)
          destination.parentFile?.mkdirs()
          val moved =
              target.renameTo(destination) ||
                  runCatching {
                    if (target.isDirectory) {
                      target.copyRecursively(destination, overwrite = true)
                      target.deleteRecursively()
                    } else {
                      target.copyTo(destination, overwrite = true)
                      target.delete()
                    }
                    true
                  }
                      .getOrDefault(false)

          if (!moved) {
            return McpToolResult.failure("Unable to move ${target.path} to the trash directory")
          }

          context.fileEvents.onFileDeleted(target)
          McpToolResult.success(
              buildJsonObject {
                    put("deleted", target.path)
                    put("trashedTo", destination.path)
                    put("purged", false)
                  }
                  .toString()
          )
        } else {
          val deleted =
              if (target.isDirectory) target.deleteRecursively() else target.delete()
          if (!deleted) {
            return McpToolResult.failure("Unable to delete ${target.path}")
          }

          context.fileEvents.onFileDeleted(target)
          McpToolResult.success(
              buildJsonObject {
                    put("deleted", target.path)
                    put("purged", true)
                  }
                  .toString()
          )
        }
      }
          .getOrElse { ToolSupport.failureOf(it) }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Shared helpers for the file tools. */
  internal object ToolSupport {

    fun resolvePath(
        arguments: JsonObject,
        context: McpToolContext,
        name: String = "path",
        required: Boolean = false,
        mustExist: Boolean = false,
    ): File {
      val raw =
          if (required) {
            Args.string(arguments, name)
          } else {
            Args.optionalString(arguments, name)
                ?: context.projectRoot?.path
                ?: context.sandbox.rootDirectories.firstOrNull()?.path
                ?: throw McpArgumentException(
                    "No project is open. Pass '$name' explicitly or open a project first."
                )
          }
      return context.sandbox.resolve(raw, mustExist = mustExist)
    }

    fun walk(
        root: File,
        maxDepth: Int,
        includeIgnored: Boolean,
        visitor: (File) -> Unit,
    ) {
      var visited = 0
      fun visit(directory: File, depth: Int) {
        if (depth > maxDepth) return
        val children = directory.listFiles()?.sortedBy { it.name.lowercase() } ?: return
        for (child in children) {
          if (visited++ > MAX_WALK_ENTRIES) return
          if (!includeIgnored && child.isDirectory && child.name in IGNORED_DIRECTORIES) {
            continue
          }
          visitor(child)
          if (child.isDirectory) {
            visit(child, depth + 1)
          }
        }
      }
      visit(root, 0)
    }

    fun treeJson(root: File, maxDepth: Int, includeIgnored: Boolean, depth: Int): JsonObject {
      val children =
          root.listFiles()
              ?.filter { includeIgnored || !(it.isDirectory && it.name in IGNORED_DIRECTORIES) }
              ?.sortedBy { it.name.lowercase() }
              ?: emptyList()

      return buildJsonObject {
        put("name", root.name.ifEmpty { root.path })
        put("path", root.path)
        put("isDirectory", root.isDirectory)
        if (root.isDirectory) {
          val truncated = depth >= maxDepth
          put(
              "children",
              buildJsonArray {
                if (!truncated) {
                  children.forEach { child ->
                    add(treeJson(child, maxDepth, includeIgnored, depth + 1))
                  }
                }
              },
          )
          put("truncated", truncated)
        }
      }
    }

    fun fileJson(file: File): JsonObject {
      return buildJsonObject {
        put("name", file.name)
        put("path", file.path)
        put("isDirectory", file.isDirectory)
        put("size", if (file.isDirectory) 0L else file.length())
        put("lastModified", file.lastModified())
      }
    }

    /** Extensions which are never treated as text. */
    private val BINARY_EXTENSIONS =
        setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic",
            "zip", "jar", "apk", "aar", "war", "7z", "rar",
            "so", "dex", "class", "kotlin_module", "jks", "keystore",
            "ttf", "otf", "woff", "woff2", "ttc",
            "mp3", "mp4", "wav", "ogg", "flac", "webm", "mkv", "avi",
            "pdf", "xz", "gz", "tar", "bin", "img", "iso",
        )

    private val MIME_TYPES =
        mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
            "ico" to "image/x-icon",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "jar" to "application/java-archive",
            "apk" to "application/vnd.android.package-archive",
            "aar" to "application/octet-stream",
            "so" to "application/x-sharedlib",
            "dex" to "application/x-dex",
            "class" to "application/java-vm",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "wav" to "audio/wav",
        )

    fun isProbablyBinary(file: File): Boolean {
      return file.extension.lowercase() in BINARY_EXTENSIONS
    }

    /**
     * Returns `true` when the file should not be read as text.
     *
     * Besides the extension check, the first 512 bytes are inspected for NUL bytes and for a high
     * ratio of undecodable UTF-8 sequences.
     */
    fun looksBinary(file: File): Boolean {
      if (isProbablyBinary(file)) return true
      if (!file.isFile) return false
      val buffer = ByteArray(512)
      val read =
          runCatching { file.inputStream().use { it.read(buffer) } }.getOrElse { return false }
      if (read <= 0) return false

      var nulls = 0
      for (index in 0 until read) {
        if (buffer[index] == 0.toByte()) nulls++
      }
      if (nulls > 0) return true

      val decoded = String(buffer, 0, read, Charsets.UTF_8)
      val replacements = decoded.count { it == '\uFFFD' }
      return replacements > 0 && replacements * 10 > read
    }

    /** Builds the metadata response for a binary file. */
    fun binaryFileJson(file: File): String {
      val preview = ByteArray(64)
      val read = runCatching { file.inputStream().use { it.read(preview) } }.getOrDefault(0)
      val hex =
          buildString {
            for (index in 0 until read.coerceAtLeast(0)) {
              val value = preview[index].toInt() and 0xFF
              append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
              if (index % 2 == 1) append(' ')
            }
          }
              .trim()

      return buildJsonObject {
            put("path", file.path)
            put("isBinary", true)
            put("sizeBytes", file.length())
            put("mime", MIME_TYPES[file.extension.lowercase()] ?: "application/octet-stream")
            put("hexPreview", hex)
          }
          .toString()
    }

    /** Number of logical lines, ignoring the empty trailing line caused by a final newline. */
    fun countLines(content: String): Int {
      if (content.isEmpty()) return 0
      val newlines = content.count { it == '\n' }
      return if (content.endsWith("\n")) newlines else newlines + 1
    }

    /** Lists [directory] and applies the `includeIgnored` filter. */
    fun filterEntries(directory: File, includeIgnored: Boolean): List<File> {
      val children = directory.listFiles() ?: return emptyList()
      return children
          .filter { includeIgnored || !(it.isDirectory && it.name in IGNORED_DIRECTORIES) }
          .sortedBy { it.name.lowercase() }
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    fun backup(file: File, context: McpToolContext, reason: String): String? {
      val trash = context.trashDir ?: return null
      if (!file.exists() || file.isDirectory) return null
      val target = trashTarget(trash, context.sandbox, file, suffix = ".$reason")
      target.parentFile?.mkdirs()
      return runCatching {
            file.copyTo(target, overwrite = true)
            target.path
          }
          .getOrNull()
    }

    fun trashTarget(
        trashDir: File,
        sandbox: com.tom.rv2ide.mcp.security.PathSandbox,
        file: File,
        suffix: String = "",
    ): File {
      val root = sandbox.rootOf(file) ?: sandbox.rootDirectories.firstOrNull()
      val relative =
          if (root == null) {
            file.name
          } else {
            runCatching { file.relativeTo(root).path }.getOrElse { file.name }
          }
      val stamp =
          java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
              .format(java.util.Date())
      return File(File(trashDir, stamp), relative + suffix)
    }

    fun isAncestor(candidate: File, file: File): Boolean {
      val parent = file.parentFile ?: return false
      return parent.path == candidate.path ||
          parent.path.startsWith(candidate.path + File.separator)
    }

    fun escape(value: String): String {
      return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun failureOf(throwable: Throwable): McpToolResult {
      return when (throwable) {
        is McpArgumentException -> McpToolResult.failure(throwable.message ?: "Invalid arguments")
        is SandboxViolationException -> McpToolResult.failure(throwable.message ?: "Sandbox violation")
        is IOException -> McpToolResult.failure("I/O error: ${throwable.message}")
        is SecurityException -> McpToolResult.failure("Permission denied: ${throwable.message}")
        else -> McpToolResult.failure("${throwable::class.java.simpleName}: ${throwable.message}")
      }
    }
  }
}
