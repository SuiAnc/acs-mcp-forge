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

import android.content.Context
import android.content.Intent
import com.tom.androidcodestudio.project.manager.ProjectInfo
import com.tom.androidcodestudio.project.manager.ProjectManager
import com.tom.rv2ide.activities.editor.EditorActivityKt
import com.tom.rv2ide.mcp.McpBuildFacade
import com.tom.rv2ide.mcp.protocol.Args
import com.tom.rv2ide.mcp.protocol.McpArgumentException
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.templates.BooleanParameter
import com.tom.rv2ide.templates.EnumParameter
import com.tom.rv2ide.templates.ITemplateProvider
import com.tom.rv2ide.templates.Language
import com.tom.rv2ide.templates.Parameter
import com.tom.rv2ide.templates.ParameterConstraint
import com.tom.rv2ide.templates.ProjectTemplate
import com.tom.rv2ide.templates.ProjectTemplateRecipeResult
import com.tom.rv2ide.templates.Sdk
import com.tom.rv2ide.templates.StringParameter
import com.tom.rv2ide.templates.Template
import com.tom.rv2ide.templates.base.MissingIdeAssetException
import com.tom.rv2ide.templates.preferences.WizardPreferences
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.TemplateRecipeExecutor
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Workspace related tools: project discovery, project creation and project opening.
 *
 * @author AndroidCodeStudio
 */
class WorkspaceTools(private val appContext: Context, private val facade: McpBuildFacade) {

  private val log = LoggerFactory.getLogger(WorkspaceTools::class.java)

  fun all(): List<McpTool> =
      listOf(
          ListProjectsTool(),
          CurrentProjectTool(),
          ProjectTemplatesTool(),
          ProjectInfoTool(),
          ProjectOpenTool(),
          ProjectCreateTool(),
          GradleTasksTool(),
      )

  // ---------------------------------------------------------------------------------------------
  // Read-only tools
  // ---------------------------------------------------------------------------------------------

  private inner class ListProjectsTool : McpTool {

    override val name = "workspace_list_projects"

    override val description =
        "List the projects known to the IDE, including the currently opened project."

    override val permission = McpPermission.READ

    override val inputSchema = Schema.objectSchema(emptyList())

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val current = currentProjectDir()
      val tracked = trackedProjects()
      val recent = recentProjects()

      val body =
          buildJsonObject {
            put("currentProject", current?.path ?: "")
            put("projectsDir", runCatching { Environment.PROJECTS_DIR.path }.getOrDefault(""))
            put(
                "tracked",
                buildJsonArray {
                  tracked.forEach { info ->
                    add(
                        buildJsonObject {
                          put("name", info.projectName)
                          put("path", info.projectDir)
                          put("type", info.projectType)
                          put("current", info.projectDir == current?.path)
                        },
                    )
                  }
                },
            )
            put(
                "recent",
                buildJsonArray {
                  recent.forEach { path ->
                    add(
                        buildJsonObject {
                          put("path", path)
                          put("current", path == current?.path)
                        },
                    )
                  }
                },
            )
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class CurrentProjectTool : McpTool {

    override val name = "workspace_current_project"

    override val description = "Return the project which is currently opened in the IDE."

    override val permission = McpPermission.READ

    override val inputSchema = Schema.objectSchema(emptyList())

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val current =
          currentProjectDir()
              ?: return McpToolResult.failure(
                  "No project is currently opened. Use project_open or pass an explicit path."
              )

      val body =
          buildJsonObject {
            put("name", current.name)
            put("path", current.path)
            put("exists", current.exists())
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class ProjectTemplatesTool : McpTool {

    override val name = "project_templates"

    override val description = "List the templates available for project_create with their parameters."

    override val permission = McpPermission.READ

    override val inputSchema = Schema.objectSchema(emptyList())

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val templates = loadProjectTemplates()
      if (templates.isEmpty()) {
        return McpToolResult.failure("No project templates are available")
      }

      val body =
          buildJsonObject {
            put(
                "templates",
                buildJsonArray {
                  templates.forEach { template ->
                    add(
                        buildJsonObject {
                          put("template", slugOf(template))
                          put("name", displayNameOf(template))
                          put(
                              "parameters",
                              buildJsonArray {
                                template.parameters.forEach { parameter ->
                                  add(
                                      buildJsonObject {
                                        put("type", parameterTypeOf(parameter))
                                        put("default", parameter.value.toString())
                                      },
                                  )
                                }
                              },
                          )
                        },
                    )
                  }
                },
            )
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class ProjectInfoTool : McpTool {

    override val name = "project_info"

    override val description = "Summarize a project: modules, Gradle files, package name and SDK levels."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
            ),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val root =
          runCatching { resolveProject(arguments, toolContext) }
              .getOrElse { return McpToolResult.failure(it.message ?: "Invalid project path") }
              ?: return McpToolResult.failure("No project is opened and no projectPath was given")

      if (!root.isDirectory) {
        return McpToolResult.failure("Not a project directory: ${root.path}")
      }

      val modules =
          root.listFiles()
              ?.filter { child ->
                child.isDirectory &&
                    (File(child, "build.gradle.kts").exists() || File(child, "build.gradle").exists())
              }
              ?.map { it.name }
              ?.sorted()
              ?: emptyList()

      val settingsFile =
          listOf("settings.gradle.kts", "settings.gradle")
              .map { File(root, it) }
              .firstOrNull { it.exists() }

      val appGradle =
          listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
              .firstOrNull { it.exists() }

      val appGradleContent = runCatching { appGradle?.readText() }.getOrNull()

      val body =
          buildJsonObject {
            put("path", root.path)
            put("name", root.name)
            put("modules", buildJsonArray { modules.forEach { add(it) } })
            put("settingsFile", settingsFile?.path ?: "")
            put("appGradleFile", appGradle?.path ?: "")
            put("packageName", extractFirst(appGradleContent, "namespace"))
            put("compileSdk", extractFirst(appGradleContent, "compileSdk"))
            put("minSdk", extractFirst(appGradleContent, "minSdk"))
            put("targetSdk", extractFirst(appGradleContent, "targetSdk"))
            put("gradleWrapper", File(root, "gradlew").exists())
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class GradleTasksTool : McpTool {

    override val name = "gradle_tasks"

    override val description =
        "Report the build service state and the Gradle tasks commonly used by the IDE."

    override val permission = McpPermission.READ

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "projectPath" to Schema.string("Project root. Defaults to the opened project."),
            ),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val root =
          runCatching { resolveProject(arguments, toolContext) }
              .getOrElse { return McpToolResult.failure(it.message ?: "Invalid project path") }
      val service = facade.serviceOrNull()
      val snapshot = facade.stateSnapshot()

      val body =
          buildJsonObject {
            put("projectPath", root?.path ?: "")
            // Report the real usability of the service instead of "bound to something": a broken
            // tooling connection used to look healthy here while every build failed.
            put("buildServiceAvailable", facade.isServiceUsable())
            put("toolingServerStarted", service?.isToolingServerStarted() ?: false)
            put("buildInProgress", snapshot?.inProgress ?: false)
            put("activeBuildId", snapshot?.id ?: "")
            put("source", "static")
            put(
                "reason",
                "The tooling API does not expose the task graph here; the list below contains the " +
                    "tasks the IDE uses most often.",
            )
            put(
                "commonTasks",
                buildJsonArray {
                  listOf(
                          "assembleDebug",
                          "assembleRelease",
                          "clean",
                          "testDebugUnitTest",
                          "lintDebug",
                          "installDebug",
                      )
                      .forEach { add(it) }
                },
            )
            put(
                "note",
                "Pass a task name to build_start. Listing the full Gradle task graph requires the " +
                    "tooling API and is not exposed yet.",
            )
          }
      return McpToolResult.success(body.toString())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Write tools
  // ---------------------------------------------------------------------------------------------

  private inner class ProjectOpenTool : McpTool {

    override val name = "project_open"

    override val description = "Open a project directory in the IDE editor."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf("projectPath" to Schema.string("Project root to open.")),
            required = listOf("projectPath"),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val root =
          runCatching { resolveProject(arguments, toolContext, required = true) }
              .getOrElse { return McpToolResult.failure(it.message ?: "Invalid project path") }
              ?: return McpToolResult.failure("Argument 'projectPath' is required")

      if (!root.isDirectory) {
        return McpToolResult.failure("Not a directory: ${root.path}")
      }

      val opened = runCatching { IProjectManager.getInstance().openProject(root) }
      if (opened.isFailure) {
        return McpToolResult.failure(
            "Unable to open ${root.path}: ${opened.exceptionOrNull()?.message}"
        )
      }

      runCatching { WizardPreferences.addRecentProject(appContext, root.path) }

      val started =
          runCatching {
                appContext.startActivity(
                    Intent(appContext, EditorActivityKt::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
              }
              .getOrDefault(false)

      val body =
          buildJsonObject {
            put("projectPath", root.path)
            put("editorStarted", started)
            if (!started) {
              put(
                  "note",
                  "The project was registered but Android blocked starting the editor from the " +
                      "background. Bring Android Code Studio to the foreground to open it.",
              )
            }
          }
      return McpToolResult.success(body.toString())
    }
  }

  private inner class ProjectCreateTool : McpTool {

    override val name = "project_create"

    override val description = "Create a new Android project from one of the built-in templates."

    override val permission = McpPermission.WRITE

    override val inputSchema =
        Schema.objectSchema(
            listOf(
                "template" to Schema.string("Template id, as returned by project_templates."),
                "projectName" to Schema.string("Name of the new project."),
                "packageName" to Schema.string("Application package name."),
                "destination" to
                    Schema.string("Parent directory. Defaults to the IDE projects directory."),
                "language" to Schema.string("kotlin or java."),
                "minSdk" to Schema.string("Minimum SDK, for example 26 or Oreo."),
                "useKts" to Schema.boolean("Use Kotlin DSL build scripts.", true),
                "openAfterCreate" to Schema.boolean("Open the project after creation.", false),
            ),
            required = listOf("template", "projectName"),
        )

    override fun call(arguments: JsonObject, toolContext: McpToolContext): McpToolResult {
      val slug = Args.string(arguments, "template")
      val projectName = Args.string(arguments, "projectName").trim()
      if (projectName.isEmpty()) {
        return McpToolResult.failure("Argument 'projectName' must not be empty")
      }

      val templates = loadProjectTemplates()
      val template =
          templates.firstOrNull { slugOf(it) == slug }
              ?: return McpToolResult.failure(
                  "Unknown template '$slug'. Available: ${templates.joinToString { slugOf(it) }}"
              )

      // Directories created by this call, used to clean up after a failed creation.
      val createdDirs = ArrayList<File>()

      val destination =
          Args.optionalString(arguments, "destination")?.let { raw ->
            val resolved =
                runCatching { toolContext.sandbox.resolve(raw) }
                    .getOrElse { error ->
                      return McpToolResult.failure(
                          "destination is invalid: $raw (${error.message ?: "not allowed"})"
                      )
                    }
            if (!toolContext.sandbox.contains(resolved)) {
              return McpToolResult.failure(
                  "destination is outside of the allowed directories: ${resolved.path}"
              )
            }
            if (!ensureDirectory(resolved, createdDirs)) {
              return McpToolResult.failure(
                  "destination does not exist and could not be created: ${resolved.path}"
              )
            }
            resolved
          }
              ?: Environment.PROJECTS_DIR

      if (!destination.isDirectory) {
        return McpToolResult.failure("Destination is not a directory: ${destination.path}")
      }

      val projectDir = File(destination, projectName)
      if (projectDir.exists() && projectDir.list()?.isNotEmpty() == true) {
        return McpToolResult.failure("Project directory already exists: ${projectDir.path}")
      }
      val projectExisted = projectDir.exists()

      // Generate into a staging directory first so that a failed recipe never leaves a half
      // created project behind.
      val stagingRoot = File(destination, ".mcp-tmp-${java.util.UUID.randomUUID()}")
      if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) {
        rollbackCreatedDirs(stagingRoot, projectDir, projectExisted, createdDirs)
        return McpToolResult.failure("Unable to create a staging directory in ${destination.path}")
      }

      val stringParameters = template.parameters.filterIsInstance<StringParameter>()
      val saveLocation =
          stringParameters.firstOrNull { ParameterConstraint.DIRECTORY in it.constraints }
              ?: run {
                rollbackCreatedDirs(stagingRoot, projectDir, projectExisted, createdDirs)
                return McpToolResult.failure("Template does not expose a save location parameter")
              }

      val packageParameter =
          stringParameters.firstOrNull { ParameterConstraint.PACKAGE in it.constraints }
      val nameParameter =
          stringParameters.firstOrNull {
            it !== saveLocation &&
                it !== packageParameter &&
                ParameterConstraint.NONEMPTY in it.constraints
          }
              ?: run {
                rollbackCreatedDirs(stagingRoot, projectDir, projectExisted, createdDirs)
                return McpToolResult.failure("Template does not expose a project name parameter")
              }

      saveLocation.setValue(stagingRoot.path)
      nameParameter.setValue(projectName)

      Args.optionalString(arguments, "packageName")?.let { packageParameter?.setValue(it) }

      val requestedUseKts = Args.optionalBoolean(arguments, "useKts")
      if (requestedUseKts != null) {
        val parameter = booleanParameterNamed(template, "msg_use_kts")
        parameter?.setValue(requestedUseKts)
      }

      Args.optionalString(arguments, "language")?.let { language ->
        val parameter = languageParameterOf(template)
        if (parameter != null && !setEnumValue(parameter, language)) {
          return McpToolResult.failure("Unknown language '$language'. Supported: Java, Kotlin")
        }
      }

      Args.optionalString(arguments, "minSdk")?.let { minSdk ->
        val parameter = minSdkParameterOf(template)
        if (parameter != null && !setSdkValue(parameter, minSdk)) {
          return McpToolResult.failure(
              "Unknown minSdk '$minSdk'. Use an API level such as 26 or a codename such as Oreo."
          )
        }
      }

      var failureStage = "recipe"
      var failure: Throwable? = null
      val projectResult =
          try {
            failureStage = "recipe.execute"
            (template.recipe.execute(TemplateRecipeExecutor()) as? ProjectTemplateRecipeResult)
          } catch (e: Exception) {
            failure = e
            null
          }

      if (projectResult == null) {
        rollbackCreatedDirs(stagingRoot, projectDir, projectExisted, createdDirs)
        log.warn("Project creation failed", failure)

        val missingAsset =
            generateSequence(failure) { it.cause }
                .filterIsInstance<MissingIdeAssetException>()
                .firstOrNull()
        if (missingAsset != null) {
          return McpToolResult.failure(
              "缺少 IDE 资产 ${missingAsset.assetPath}，请重装或补齐资产后重试"
          )
        }

        val details =
            failure?.let {
              "${it::class.java.simpleName}: ${it.message ?: "no message"}" +
                  it.stackTrace.take(3).joinToString("") { frame -> " at $frame" }
            } ?: "the template recipe returned null"
        return McpToolResult.failure(
            "Project creation failed (template=$slug, destination=${destination.path}, " +
                "stage=$failureStage): $details"
        )
      }

      val stagedDir = projectResult.data.projectDir
      val createdDir = File(destination, projectName)
      val moved =
          stagedDir.renameTo(createdDir) ||
              runCatching {
                stagedDir.copyRecursively(createdDir, overwrite = true)
                stagedDir.deleteRecursively()
                true
              }
                  .getOrDefault(false)

      if (!moved) {
        val staged = stagedDir.path
        rollbackCreatedDirs(stagingRoot, projectDir, projectExisted, createdDirs)
        return McpToolResult.failure(
            "Project was generated but could not be moved from $staged to ${createdDir.path}"
        )
      }
      stagingRoot.deleteRecursively()

      runCatching {
        val manager = ProjectManager.getInstance(appContext)
        manager.addProjectBlocking(
            manager.createTemplateProjectInfo(projectName, createdDir.path, "android")
        )
        WizardPreferences.addRecentProject(appContext, createdDir.path)
      }
          .onFailure { log.warn("Unable to register the created project", it) }

      if (Args.boolean(arguments, "openAfterCreate", false)) {
        runCatching { IProjectManager.getInstance().openProject(createdDir) }
      }

      val settingsFileName =
          if (File(createdDir, "settings.gradle.kts").exists()) {
            "settings.gradle.kts"
          } else if (File(createdDir, "settings.gradle").exists()) {
            "settings.gradle"
          } else {
            ""
          }

      val body =
          buildJsonObject {
            put("projectPath", createdDir.path)
            put("projectName", projectName)
            put("template", slug)
            if (requestedUseKts != null) {
              put("useKtsRequested", requestedUseKts)
            }
            put("useKtsSource", if (requestedUseKts == null) "default" else "argument")
            put("useKtsApplied", settingsFileName.endsWith(".kts"))
            put("settingsFile", settingsFileName)
          }
      return McpToolResult.success(body.toString())
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private fun resolveProject(
      arguments: JsonObject,
      toolContext: McpToolContext,
      required: Boolean = false,
  ): File? {
    val explicit = Args.optionalString(arguments, "projectPath")
    if (explicit != null) {
      return toolContext.sandbox.resolve(explicit, mustExist = true)
    }
    if (required) {
      throw McpArgumentException("Argument 'projectPath' is required")
    }
    return currentProjectDir() ?: toolContext.projectRoot
  }

  /**
   * Creates [dir] (and its missing parents) one level at a time, recording every directory which
   * was actually created so that a failed project creation can remove the leftovers.
   */
  private fun ensureDirectory(dir: File, created: MutableList<File>): Boolean {
    if (dir.isDirectory) return true

    val missing = ArrayList<File>()
    var current: File? = dir
    while (current != null && !current.exists()) {
      missing.add(current)
      current = current.parentFile
    }

    missing.asReversed().forEach { candidate ->
      if (candidate.mkdir() && candidate.isDirectory) {
        created.add(candidate)
      }
    }
    return dir.isDirectory
  }

  /**
   * Removes the staging directory and the empty directories which this call created.
   *
   * Directories which existed before the call (and their contents) are never removed.
   */
  private fun rollbackCreatedDirs(
      stagingRoot: File,
      projectDir: File,
      projectExisted: Boolean,
      createdDirs: List<File>,
  ) {
    runCatching { stagingRoot.deleteRecursively() }
    if (!projectExisted) {
      runCatching { projectDir.deleteRecursively() }
    }
    // Bottom-up: only the directories which are empty now.
    createdDirs.asReversed().forEach { dir ->
      runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
    }
  }

  private fun currentProjectDir(): File? {
    return runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
  }

  private fun trackedProjects(): List<ProjectInfo> {
    return runCatching { ProjectManager.getInstance(appContext).getTrackedProjectsBlocking() }
        .getOrDefault(emptyList())
  }

  private fun recentProjects(): List<String> {
    return runCatching { WizardPreferences.getRecentProjects(appContext) }
        .getOrDefault(emptyList())
  }

  private fun loadProjectTemplates(): List<ProjectTemplate> {
    return runCatching {
          ITemplateProvider.getInstance().getTemplates().filterIsInstance<ProjectTemplate>()
        }
        .getOrDefault(emptyList())
  }

  private fun slugOf(template: Template<*>): String {
    val entry =
        runCatching { appContext.resources.getResourceEntryName(template.templateName) }.getOrNull()
    return (entry ?: "template_${System.identityHashCode(template)}")
        .removePrefix("template_")
        .lowercase()
  }

  private fun displayNameOf(template: Template<*>): String {
    return runCatching { appContext.getString(template.templateName) }.getOrDefault(slugOf(template))
  }

  private fun parameterTypeOf(parameter: Parameter<*>): String {
    return when (parameter) {
      is StringParameter -> "string"
      is BooleanParameter -> "boolean"
      is EnumParameter<*> -> "enum"
      else -> parameter::class.java.simpleName
    }
  }

  /**
   * Finds the boolean parameter which corresponds to [entryName] (for example `msg_use_kts`).
   *
   * Templates name their parameters with a string resource id, so the resource entry name is the
   * primary match. The remaining fallbacks keep the behaviour working for templates which expose
   * the id directly or only a single boolean switch.
   */
  private fun booleanParameterNamed(template: Template<*>, entryName: String): BooleanParameter? {
    val booleans = template.parameters.filterIsInstance<BooleanParameter>()
    if (booleans.isEmpty()) return null

    booleans
        .firstOrNull { parameter ->
          runCatching { appContext.resources.getResourceEntryName(parameter.name) }.getOrNull() ==
              entryName
        }
        ?.let { return it }

    val expectedId = appContext.resources.getIdentifier(entryName, "string", appContext.packageName)
    if (expectedId != 0) {
      booleans.firstOrNull { it.name == expectedId }?.let { return it }
    }

    booleans.singleOrNull()?.let { return it }
    return booleans.firstOrNull { it.default }
  }

  private fun languageParameterOf(template: Template<*>): EnumParameter<*>? {
    return template.parameters
        .filterIsInstance<EnumParameter<*>>()
        .firstOrNull { it.value is Language }
  }

  private fun minSdkParameterOf(template: Template<*>): EnumParameter<*>? {
    return template.parameters
        .filterIsInstance<EnumParameter<*>>()
        .firstOrNull { it.value is Sdk }
  }

  @Suppress("UNCHECKED_CAST")
  private fun setEnumValue(parameter: EnumParameter<*>, rawValue: String): Boolean {
    val constants = parameter.value.javaClass.enumConstants ?: return false
    val match =
        constants.firstOrNull { (it as Enum<*>).name.equals(rawValue.trim(), ignoreCase = true) }
            ?: return false
    (parameter as Parameter<Any>).setValue(match)
    return true
  }

  @Suppress("UNCHECKED_CAST")
  private fun setSdkValue(parameter: EnumParameter<*>, rawValue: String): Boolean {
    val trimmed = rawValue.trim()
    val apiLevel = trimmed.toIntOrNull()
    if (apiLevel != null) {
      val match = Sdk.values().firstOrNull { it.apiLevel() == apiLevel } ?: return false
      (parameter as Parameter<Any>).setValue(match)
      return true
    }
    return setEnumValue(parameter, trimmed)
  }

  private fun extractFirst(content: String?, key: String): String {
    if (content == null) return ""
    val pattern = Regex("""$key\s*=?\s*([^\s,)]+)""")
    return pattern.find(content)?.groupValues?.getOrNull(1)?.trim()?.trim('"') ?: ""
  }
}
