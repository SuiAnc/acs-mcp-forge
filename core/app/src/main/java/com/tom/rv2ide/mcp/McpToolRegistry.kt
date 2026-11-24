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
import com.tom.androidcodestudio.project.manager.ProjectManager
import com.tom.rv2ide.mcp.protocol.McpProtocol
import com.tom.rv2ide.mcp.security.PathSandbox
import com.tom.rv2ide.mcp.tools.BuildTools
import com.tom.rv2ide.mcp.tools.DefaultMcpToolContext
import com.tom.rv2ide.mcp.tools.FileTools
import com.tom.rv2ide.mcp.tools.McpTool
import com.tom.rv2ide.mcp.tools.McpToolContext
import com.tom.rv2ide.mcp.tools.WorkspaceTools
import com.tom.rv2ide.projects.FileManager
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.templates.preferences.WizardPreferences
import com.tom.rv2ide.utils.Environment
import java.io.File
import org.slf4j.LoggerFactory

/**
 * Provides the MCP tool list and the execution context for every tool call.
 *
 * The sandbox roots are derived from the project that is opened in the IDE, the projects tracked by
 * the template creator, the IDE project directories and the recently opened projects.
 *
 * @author AndroidCodeStudio
 */
class McpToolRegistry(private val context: Context, private val facade: McpBuildFacade) :
    McpProtocol.ToolProvider {

  private val log = LoggerFactory.getLogger(McpToolRegistry::class.java)

  private val fileTools = FileTools.all()
  private val workspaceTools = WorkspaceTools(context, facade).all()
  private val buildTools = BuildTools(facade).all()
  private val fileEvents = McpFileEvents(context)

  override fun availableTools(): List<McpTool> = fileTools + workspaceTools + buildTools

  override fun contextFor(tool: McpTool): McpToolContext {
    return DefaultMcpToolContext(
        sandbox = PathSandbox(sandboxRoots()),
        projectRoot = currentProjectDir(),
        trashDir = trashDir(),
        fileEvents = fileEvents,
        maxReadBytes = MAX_READ_BYTES,
        documentContentProvider = { file -> openDocumentContent(file) },
    )
  }

  /** Directories exposed to the MCP clients. */
  fun sandboxRoots(): List<File> {
    val roots = LinkedHashSet<File>()

    currentProjectDir()?.let { roots.add(it) }

    runCatching { ProjectManager.getInstance(context).getTrackedProjectsBlocking() }
        .getOrDefault(emptyList())
        .forEach { info -> roots.add(File(info.projectDir)) }

    runCatching { Environment.PROJECTS_DIR }.getOrNull()?.let { roots.add(it) }
    runCatching { Environment.AT_ACSHOME_PROJECTS }.getOrNull()?.let { roots.add(it) }

    runCatching { WizardPreferences.getRecentProjects(context) }
        .getOrDefault(emptyList())
        .forEach { path -> roots.add(File(path)) }

    return roots.toList()
  }

  fun currentProjectDir(): File? {
    return runCatching { IProjectManager.getInstance().projectDir }.getOrNull()
  }

  fun trashDir(): File {
    val dir = File(context.filesDir, "mcp/trash")
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir
  }

  private fun openDocumentContent(file: File): String? {
    return runCatching {
          val path = file.toPath()
          if (FileManager.getActiveDocument(path) != null) {
            FileManager.getDocumentContents(path)
          } else {
            null
          }
        }
        .onFailure { log.debug("Unable to read the editor buffer for {}", file.path) }
        .getOrNull()
  }

  companion object {

    /** Maximum number of bytes returned by `file_read`. */
    const val MAX_READ_BYTES = 2L * 1024L * 1024L
  }
}
