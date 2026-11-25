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

import com.tom.rv2ide.git.GitManager
import com.tom.rv2ide.mcp.protocol.Args
import com.tom.rv2ide.mcp.protocol.McpArgumentException
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Git tools backed by [GitManager] (JGit).
 *
 * Read-only tools are available like the other read tools. Mutating tools require the "allow write
 * operations" switch, and the network tools (`git_fetch` / `git_pull` / `git_push`) additionally
 * require the separate "allow network operations" switch, because they contact the remote.
 *
 * Credentials are never accepted as tool arguments: JGit uses the credentials embedded in the
 * remote URL (or none), and failures are reported verbatim so the user can fix the remote.
 *
 * @author AndroidCodeStudio
 */
class GitTools {

  private val log = LoggerFactory.getLogger(GitTools::class.java)

  fun all(): List<McpTool> =
      listOf(
          GitStatusTool(),
          GitDiffTool(),
          GitLogTool(),
          GitBranchesTool(),
          GitStageTool(),
          GitCommitTool(),
          GitCheckoutTool(),
          GitDiscardTool(),
          GitFetchTool(),
          GitPullTool(),
          GitPushTool(),
      )

  // ---------------------------------------------------------------------------------------------
  // Read only tools
  // ---------------------------------------------------------------------------------------------

  private inner class GitStatusTool : McpTool {

    override val name = "git_status"

    override val description = "Show the branch, whether the worktree is clean and the changed files."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf("projectPath" to Schema.string("Project root. Defaults to the opened project.")),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val branch = manager.getCurrentBranch()
        val changes = manager.getChangedFiles()
        val remotes = manager.getRemotes().map { it.name }

        val body =
            buildJsonObject {
              put("repository", repo.path)
              put("branch", branch ?: "")
              put("clean", changes.isEmpty())
              put("remotes", buildJsonArray { remotes.forEach { add(it) } })
              put(
                  "changes",
                  buildJsonArray {
                    changes.forEach { change ->
                      add(
                          buildJsonObject {
                            put("path", change.path)
                            put("type", change.changeType.name.lowercase())
                            put("staged", change.isStaged)
                          },
                      )
                    }
                  },
              )
            }
        McpToolResult.success(body.toString())
      }
    }
  }

  private inner class GitDiffTool : McpTool {

    override val name = "git_diff"

    override val description =
        "Show the unified diff of one file, or of all changed files (truncated to 8 KB)."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "path" to Schema.string("File to diff. Relative to the repository root."),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val requested = Args.optionalString(arguments, "path")
        val text =
            if (requested != null) {
              val file = resolveInside(repo, requested, context)
              if (!ToolSupport.isWithin(repo, file)) {
                throw McpArgumentException("Path is outside of the repository: ${file.path}")
              }
              manager.getFileDiff(file.relativeTo(repo).path)
            } else {
              val files = manager.getChangedFiles().map { it.path }.take(MAX_DIFF_FILES)
              files.joinToString("\n") { path ->
                val diff = manager.getFileDiff(path)
                if (diff.isBlank()) "" else "=== $path ===\n$diff"
              }
            }

        McpToolResult.success(
            buildJsonObject {
                  put("repository", repo.path)
                  put("truncated", text.length > MAX_DIFF_CHARS)
                  put("diff", text.take(MAX_DIFF_CHARS))
                }
                .toString()
        )
      }
    }
  }

  private inner class GitLogTool : McpTool {

    override val name = "git_log"

    override val description = "Show the most recent commits."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "count" to Schema.integer("Number of commits to return.", 20),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val count = Args.integer(arguments, "count", 20).coerceIn(1, 100)
        val commits = manager.getCommitHistory(count)
        val body =
            buildJsonObject {
              put("repository", repo.path)
              put(
                  "commits",
                  buildJsonArray {
                    commits.forEach { commit ->
                      add(
                          buildJsonObject {
                            put("hash", commit.hash)
                            put("shortHash", commit.shortHash)
                            put("message", commit.message)
                            put("author", commit.author)
                            put("timestamp", commit.timestamp)
                          },
                      )
                    }
                  },
              )
            }
        McpToolResult.success(body.toString())
      }
    }
  }

  private inner class GitBranchesTool : McpTool {

    override val name = "git_branches"

    override val description = "List local branches and mark the current one."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf("projectPath" to Schema.string("Project root. Defaults to the opened project.")),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val current = manager.getCurrentBranch()
        val branches = manager.getAllBranches()
        val body =
            buildJsonObject {
              put("repository", repo.path)
              put("current", current ?: "")
              put(
                  "branches",
                  buildJsonArray {
                    branches.forEach { branch -> add(branch) }
                  },
              )
            }
        McpToolResult.success(body.toString())
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Write tools
  // ---------------------------------------------------------------------------------------------

  private inner class GitStageTool : McpTool {

    override val name = "git_stage"

    override val description = "Stage or unstage files. Use all=true to stage every change."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "paths" to Schema.stringArray("Files to stage or unstage."),
                "all" to Schema.boolean("Stage all changes.", false),
                "unstage" to Schema.boolean("Unstage instead of stage.", false),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val unstage = Args.boolean(arguments, "unstage", false)
        val all = Args.boolean(arguments, "all", false)
        val paths = Args.stringList(arguments, "paths")

        val affected = ArrayList<String>()
        if (all) {
          if (unstage) {
            manager.getChangedFiles().filter { it.isStaged }.forEach { change ->
              if (manager.unstageFile(change.path)) affected.add(change.path)
            }
          } else if (manager.stageAllFiles()) {
            manager.getChangedFiles().forEach { affected.add(it.path) }
          }
        } else {
          if (paths.isEmpty()) {
            throw McpArgumentException("Provide 'paths' or set all=true")
          }
          paths.forEach { raw ->
            val file = resolveInside(repo, raw, context)
            val relative = file.relativeTo(repo).path
            val ok = if (unstage) manager.unstageFile(relative) else manager.stageFile(relative)
            if (ok) affected.add(relative)
          }
        }

        McpToolResult.success(
            buildJsonObject {
                  put("repository", repo.path)
                  put("unstage", unstage)
                  put("affected", buildJsonArray { affected.forEach { add(it) } })
                }
                .toString()
        )
      }
    }
  }

  private inner class GitCommitTool : McpTool {

    override val name = "git_commit"

    override val description = "Commit the staged changes."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "message" to Schema.string("Commit message."),
                "author" to Schema.string("Author name. Defaults to the configured user."),
                "email" to Schema.string("Author email. Defaults to the configured user."),
            ),
            required = listOf("message"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val message = Args.string(arguments, "message").trim()
        if (message.isEmpty()) {
          throw McpArgumentException("Commit message must not be empty")
        }
        val (defaultName, defaultEmail) = manager.getUserConfig() ?: ("User" to "user@example.com")
        val author = Args.optionalString(arguments, "author") ?: defaultName
        val email = Args.optionalString(arguments, "email") ?: defaultEmail

        if (manager.getChangedFiles().none { it.isStaged }) {
          McpToolResult.failure("Nothing is staged. Use git_stage first.")
        } else {
          val ok = manager.commit(message, author, email)
          if (ok) {
            McpToolResult.success(
                buildJsonObject {
                      put("repository", repo.path)
                      put("committed", true)
                      put("message", message)
                      put("author", author)
                    }
                    .toString()
            )
          } else {
            McpToolResult.failure("Commit failed. Check the IDE log for the JGit error.")
          }
        }
      }
    }
  }

  private inner class GitCheckoutTool : McpTool {

    override val name = "git_checkout"

    override val description = "Switch to another local branch. Refuses while the worktree is dirty."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "branch" to Schema.string("Branch to check out."),
                "force" to Schema.boolean("Check out even with uncommitted changes.", false),
            ),
            required = listOf("branch"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val branch = Args.string(arguments, "branch").trim()
        val force = Args.boolean(arguments, "force", false)
        if (!force && manager.getChangedFiles().isNotEmpty()) {
          McpToolResult.failure(
              "The worktree has uncommitted changes. Commit/stash them first, or pass force=true."
          )
        } else if (manager.checkoutBranch(branch)) {
          McpToolResult.success(
              buildJsonObject {
                    put("repository", repo.path)
                    put("branch", branch)
                  }
                  .toString()
          )
        } else {
          McpToolResult.failure("Unable to check out branch '$branch'")
        }
      }
    }
  }

  private inner class GitDiscardTool : McpTool {

    override val name = "git_discard"

    override val description = "Discard the local changes of one file. This cannot be undone."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "path" to Schema.string("File to restore (relative to the repository root)."),
            ),
            required = listOf("path"),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val raw = Args.string(arguments, "path")
        val file = resolveInside(repo, raw, context)
        val relative = file.relativeTo(repo).path
        if (manager.discardChanges(relative)) {
          McpToolResult.success(
              buildJsonObject {
                    put("repository", repo.path)
                    put("discarded", relative)
                  }
                  .toString()
          )
        } else {
          McpToolResult.failure("Unable to discard changes of $relative")
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Network tools
  // ---------------------------------------------------------------------------------------------

  private inner class GitFetchTool : McpTool {

    override val name = "git_fetch"

    override val description = "Fetch from a remote. Requires the network switch and configured remote."

    override val permission = McpPermission.NETWORK

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "remote" to Schema.string("Remote name, defaults to origin."),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val remote = Args.optionalString(arguments, "remote") ?: "origin"
        requireRemote(manager, remote)
        val result = manager.fetch(remote)
        report(repo, result.success, result.message)
      }
    }
  }

  private inner class GitPullTool : McpTool {

    override val name = "git_pull"

    override val description = "Pull from a remote. Requires the network switch and configured remote."

    override val permission = McpPermission.NETWORK

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "remote" to Schema.string("Remote name, defaults to origin."),
                "branch" to Schema.string("Remote branch, defaults to the current branch."),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val remote = Args.optionalString(arguments, "remote") ?: "origin"
        val branch = Args.optionalString(arguments, "branch")
        requireRemote(manager, remote)
        val result = manager.pull(remote, branch)
        report(repo, result.success, result.message)
      }
    }
  }

  private inner class GitPushTool : McpTool {

    override val name = "git_push"

    override val description = "Push to a remote. Requires the network switch and configured remote."

    override val permission = McpPermission.NETWORK

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "remote" to Schema.string("Remote name, defaults to origin."),
                "branch" to Schema.string("Branch to push, defaults to the current branch."),
            ),
        )

    override fun call(arguments: JsonObject, context: McpToolContext): McpToolResult {
      return withRepository(arguments, context) { manager, repo ->
        val remote = Args.optionalString(arguments, "remote") ?: "origin"
        val branch = Args.optionalString(arguments, "branch")
        requireRemote(manager, remote)
        val result = manager.push(remote, branch)
        report(repo, result.success, result.message)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private inline fun withRepository(
      arguments: JsonObject,
      context: McpToolContext,
      block: (GitManager, File) -> McpToolResult,
  ): McpToolResult {
    val repo =
        runCatching { resolveRepository(arguments, context) }
            .getOrElse { error -> return McpToolResult.failure(error.message ?: "Invalid repository") }

    val manager = GitManager(repo.path)
    return try {
      if (!manager.openRepository()) {
        McpToolResult.failure("Not a Git repository: ${repo.path}")
      } else {
        block(manager, repo)
      }
    } catch (e: McpArgumentException) {
      McpToolResult.failure(e.message ?: "Invalid arguments")
    } catch (e: Exception) {
      log.warn("Git tool failed", e)
      McpToolResult.failure("${e::class.java.simpleName}: ${e.message ?: "unexpected error"}")
    } finally {
      runCatching { manager.close() }
    }
  }

  private fun resolveRepository(arguments: JsonObject, context: McpToolContext): File {
    val explicit = Args.optionalString(arguments, "projectPath")
    val root =
        if (explicit != null) {
          context.sandbox.resolve(explicit, mustExist = true, mustBeDirectory = true)
        } else {
          context.projectRoot
              ?: throw McpArgumentException(
                  "No project is opened and no projectPath was given"
              )
        }

    if (!File(root, ".git").exists()) {
      throw McpArgumentException("Not a Git repository: ${root.path}")
    }
    return root
  }

  /** Resolves [raw] (absolute or relative to the repository) and makes sure it stays inside it. */
  private fun resolveInside(repo: File, raw: String, context: McpToolContext): File {
    val candidate = if (File(raw).isAbsolute) File(raw) else File(repo, raw)
    val resolved = context.sandbox.resolve(candidate.path, mustExist = false)
    if (!ToolSupport.isWithin(repo, resolved)) {
      throw McpArgumentException("Path is outside of the repository: ${resolved.path}")
    }
    return resolved
  }

  private fun requireRemote(manager: GitManager, remote: String) {
    val remotes = manager.getRemotes().map { it.name }
    if (remote !in remotes) {
      throw McpArgumentException(
          "Remote '$remote' is not configured. Available: ${remotes.ifEmpty { listOf("none") }}"
      )
    }
  }

  private fun report(repo: File, success: Boolean, message: String): McpToolResult {
    val body =
        buildJsonObject {
          put("repository", repo.path)
          put("success", success)
          put("message", message)
        }
    return if (success) McpToolResult.success(body.toString()) else McpToolResult.failure(message)
  }

  private companion object {

    const val MAX_DIFF_FILES = 10
    const val MAX_DIFF_CHARS = 8000
  }

  /** Shared helpers for the git tools. */
  private object ToolSupport {

    fun isWithin(repository: File, file: File): Boolean {
      val rootPath = repository.canonicalFile.path
      val filePath = file.canonicalFile.path
      return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
  }
}
