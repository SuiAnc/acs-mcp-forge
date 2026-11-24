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

import com.tom.rv2ide.mcp.McpBuildFacade
import com.tom.rv2ide.mcp.protocol.Args
import com.tom.rv2ide.mcp.protocol.McpArgumentException
import com.tom.rv2ide.projects.IProjectManager
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Build tools backed by [McpBuildFacade].
 *
 * @author AndroidCodeStudio
 */
class BuildTools(private val facade: McpBuildFacade) {

  fun all(): List<McpTool> =
      listOf(BuildStartTool(), BuildStatusTool(), BuildLogsTool(), BuildCancelTool(), BuildApkTool())

  private inner class BuildStartTool : McpTool {

    override val name = "build_start"

    override val description =
        "Start a Gradle build. Returns a buildId which can be polled with build_status/build_logs."

    override val permission = McpPermission.BUILD

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "task" to Schema.string("Gradle task to execute, defaults to assembleDebug."),
                "tasks" to Schema.stringArray("Additional Gradle tasks to execute."),
                "variant" to Schema.string("Build variant, used to build the task name."),
                "queueIfBusy" to
                    Schema.boolean(
                        "Wait for the running build to finish instead of failing immediately.",
                        false,
                    ),
                "validateOnly" to
                    Schema.boolean(
                        "Only validate that the requested tasks exist; do not run a build.",
                        false,
                    ),
            ),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val projectDir = resolveProjectDir(arguments, toolContext)
      if (!toolContext.sandbox.contains(projectDir)) {
        return McpToolResult.failure(
            "Project is outside of the allowed directories: ${projectDir.path}"
        )
      }

      val requested = ArrayList<String>()
      Args.optionalString(arguments, "task")?.let { requested.add(it) }
      requested.addAll(Args.stringList(arguments, "tasks"))

      val variant = Args.optionalString(arguments, "variant")
      val tasks =
          if (requested.isEmpty()) {
            listOf(if (variant != null) "assemble${variant.replaceFirstChar { it.uppercase() }}" else "assembleDebug")
          } else {
            requested.map { task ->
              if (variant != null && task == "assembleDebug") {
                "assemble${variant.replaceFirstChar { it.uppercase() }}"
              } else {
                task
              }
            }
          }

      if (Args.boolean(arguments, "validateOnly", false)) {
        return validateTasks(projectDir, tasks)
      }

      val queueIfBusy = Args.boolean(arguments, "queueIfBusy", false)
      val result = facade.startBuild(projectDir, tasks, queueIfBusy)
      if (!result.ok) {
        return McpToolResult.failure(result.message)
      }

      val body =
          buildJsonObject {
            put("buildId", result.buildId ?: "")
            put("projectPath", projectDir.path)
            put("tasks", buildJsonArray { tasks.forEach { add(it) } })
            put("message", result.message)
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class BuildStatusTool : McpTool {

    override val name = "build_status"

    override val description = "Report the state of the current (or last) build."

    override val permission = McpPermission.BUILD

    override val inputSchema =
        Schema.objectSchema(
            listOf("buildId" to Schema.string("Build id returned by build_start.")),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val requestedId = Args.optionalString(arguments, "buildId")
      val unknownId = unknownBuildIdResult(requestedId)
      if (unknownId != null) {
        return unknownId
      }

      val currentId = facade.activeBuildId()
      val snapshot =
          facade.stateSnapshot()
              ?: return McpToolResult.failure(
                  "The Gradle build service is not available. Start a build first."
              )

      val now = System.currentTimeMillis()
      val startedAt = snapshot.startedAt
      val finishedAt = snapshot.finishedAt
      val duration =
          when {
            startedAt <= 0L -> 0L
            finishedAt > 0L -> finishedAt - startedAt
            else -> now - startedAt
          }

      val body =
          buildJsonObject {
            put("buildId", currentId ?: "")
            put("origin", snapshot.origin)
            put("state", snapshot.state)
            put("inProgress", snapshot.inProgress)
            put("tasks", buildJsonArray { snapshot.tasks.forEach { add(it) } })
            put("startedAt", startedAt)
            put("finishedAt", finishedAt)
            put("durationMs", duration)
            put("projectPath", snapshot.projectPath.ifBlank { facade.currentProjectDir?.path ?: "" })
            snapshot.reason?.let { put("reason", it) }
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class BuildLogsTool : McpTool {

    override val name = "build_logs"

    override val description = "Return the most recent Gradle build output lines."

    override val permission = McpPermission.BUILD

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "buildId" to Schema.string("Build id returned by build_start."),
                "tail" to Schema.integer("Number of trailing lines to return.", 200),
                "verbose" to
                    Schema.boolean(
                        "Also return the raw tooling progress events (task start/finish).",
                        false,
                    ),
            ),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val unknownId = unknownBuildIdResult(Args.optionalString(arguments, "buildId"))
      if (unknownId != null) {
        return unknownId
      }

      val tail = Args.integer(arguments, "tail", 200).coerceIn(1, 2000)
      val verbose = Args.boolean(arguments, "verbose", false)
      val lines = facade.logSnapshot(tail)
      val events = if (verbose) facade.eventSnapshot(tail) else emptyList()

      val body =
          buildJsonObject {
            put("buildId", facade.activeBuildId() ?: "")
            put("count", lines.size)
            put("lines", buildJsonArray { lines.forEach { add(it) } })
            if (verbose) {
              put("eventCount", events.size)
              put("events", buildJsonArray { events.forEach { add(it) } })
            }
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class BuildCancelTool : McpTool {

    override val name = "build_cancel"

    override val description = "Cancel the running Gradle build."

    override val permission = McpPermission.BUILD

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "buildId" to Schema.string("Build id to cancel."),
                "force" to
                    Schema.boolean("Clear the build record even if no build is running.", false),
            )
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val unknownId = unknownBuildIdResult(Args.optionalString(arguments, "buildId"))
      if (unknownId != null) {
        return unknownId
      }

      val force = Args.boolean(arguments, "force", false)
      val result = facade.cancelBuild(force)
      return if (result.ok) {
        McpToolResult.success(
            buildJsonObject {
                  put("buildId", facade.activeBuildId() ?: "")
                  put("enqueued", result.enqueued)
                  result.reason?.let { put("reason", it) }
                  put("message", result.message)
                }
                .toString()
        )
      } else {
        McpToolResult.failure(result.message)
      }
    }
  }

  private inner class BuildApkTool : McpTool {

    override val name = "build_apk"

    override val description = "Locate the APK produced by the last build of the given project."

    override val permission = McpPermission.BUILD

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
                "variant" to Schema.string("Build variant to look for, defaults to debug."),
            ),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val projectDir = resolveProjectDir(arguments, toolContext)
      val variant = Args.optionalString(arguments, "variant") ?: "debug"

      val apk = facade.findApk(projectDir, variant)
          ?: return McpToolResult.failure(
              "No APK found for variant '$variant' in ${projectDir.path}. " +
                  "Run build_start with assemble${variant.replaceFirstChar { it.uppercase() }} first."
          )

      val body =
          buildJsonObject {
            put("apkPath", apk.path)
            put("sizeBytes", apk.length())
            put("lastModified", apk.lastModified())
            put("variant", variant)
          }
      return McpToolResult.success(body.toString())
    }
  }

  /**
   * Validates [tasks] without running a build (`build_start(validateOnly=true)`).
   *
   * The tooling API surface used by the IDE does not expose the full task graph (see
   * `gradle_tasks`), so the validation is based on the task name patterns the IDE supports. The
   * project is initialized first so that configuration errors are reported here rather than in a
   * full build.
   */
  private fun validateTasks(projectDir: File, tasks: List<String>): McpToolResult {
    val service = facade.ensureService()
    if (service == null) {
      return validationResult(
          tasks = tasks,
          projectDir = projectDir,
          reason = "The Gradle build service is not available",
      )
    }

    if (!facade.ensureToolingServer(service)) {
      return validationResult(
          tasks = tasks,
          projectDir = projectDir,
          reason = "The tooling API server could not be started",
      )
    }

    val initialized = facade.ensureProjectInitialized(service, projectDir)
    if (!initialized.ok) {
      return validationResult(tasks = tasks, projectDir = projectDir, reason = initialized.message)
    }

    val unknown = tasks.filterNot(::isPlausibleTaskName)
    return validationResult(
        tasks = tasks,
        projectDir = projectDir,
        reason = if (unknown.isEmpty()) null else "Task not found: ${unknown.joinToString(", ")}",
    )
  }

  /** Builds the JSON payload returned by `build_start(validateOnly=true)`. */
  private fun validationResult(
      tasks: List<String>,
      projectDir: File,
      reason: String?,
  ): McpToolResult {
    val body =
        buildJsonObject {
          put("valid", reason == null)
          put("source", "whitelist")
          put("projectPath", projectDir.path)
          put("tasks", buildJsonArray { tasks.forEach { add(it) } })
          if (reason != null) {
            put("reason", reason)
          }
        }
    return McpToolResult.success(body.toString())
  }

  /** Whether [task] matches the task name patterns which the IDE knows about. */
  private fun isPlausibleTaskName(task: String): Boolean {
    val name = task.substringAfterLast(':').trim()
    if (name.isEmpty()) return false
    return TASK_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
  }

  companion object {

    /**
     * Task name prefixes accepted by `build_start(validateOnly=true)`.
     *
     * The tooling API surface used here does not expose the task graph, so this list is the best
     * available approximation. It is intentionally permissive and only rejects obvious typos.
     */
    private val TASK_PREFIXES =
        arrayOf(
            "assemble",
            "bundle",
            "build",
            "check",
            "clean",
            "compile",
            "connected",
            "dex",
            "generate",
            "install",
            "lint",
            "merge",
            "package",
            "test",
            "uninstall",
        )
  }

  /**
   * Returns a failure result when [requestedId] is given but does not belong to the current build.
   *
   * Previously an unknown id silently fell back to the current build, which made automation read
   * the wrong build's data.
   */
  private fun unknownBuildIdResult(requestedId: String?): McpToolResult? {
    if (requestedId.isNullOrBlank()) return null
    val currentId = facade.activeBuildId()
    if (currentId == null) {
      return McpToolResult.failure(
          "Unknown buildId: $requestedId. No build has been started in this session."
      )
    }
    if (requestedId != currentId) {
      return McpToolResult.failure(
          "Unknown buildId: $requestedId. The current build is '$currentId'."
      )
    }
    return null
  }

  private fun resolveProjectDir(arguments: JsonObject, toolContext: McpToolContext): File {
    val explicit = Args.optionalString(arguments, "projectPath")
    if (explicit != null) {
      return toolContext.sandbox.resolve(explicit, mustExist = true, mustBeDirectory = true)
    }

    val current =
        runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
            ?: toolContext.projectRoot
            ?: throw McpArgumentException(
                "Argument 'projectPath' is required when no project is opened"
            )

    if (!current.isDirectory) {
      throw McpArgumentException("Not a project directory: ${current.path}")
    }
    return current
  }
}
