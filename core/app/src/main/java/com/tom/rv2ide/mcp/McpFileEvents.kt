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
import com.tom.rv2ide.eventbus.events.file.FileCreationEvent
import com.tom.rv2ide.eventbus.events.file.FileDeletionEvent
import com.tom.rv2ide.eventbus.events.file.FileRenameEvent
import com.tom.rv2ide.events.ListProjectFilesRequestEvent
import com.tom.rv2ide.mcp.tools.FileEventSink
import com.tom.rv2ide.projects.FileManager
import java.io.File
import org.greenrobot.eventbus.EventBus

/**
 * Keeps the IDE in sync when the MCP server changes files: the file tree is refreshed and the
 * active documents are updated, exactly like the file tree actions do.
 *
 * @author AndroidCodeStudio
 */
class McpFileEvents(private val context: Context) : FileEventSink {

  override fun onFileCreated(file: File) {
    val event = FileCreationEvent(file)
    event.put(Context::class.java, context)
    EventBus.getDefault().post(event)
  }

  override fun onFileDeleted(file: File) {
    val event = FileDeletionEvent(file)
    FileManager.onFileDeleted(event)
    event.put(Context::class.java, context)
    EventBus.getDefault().post(event)
  }

  override fun onFileRenamed(source: File, destination: File) {
    val event = FileRenameEvent(source, destination)
    FileManager.onFileRenamed(event)
    event.put(Context::class.java, context)
    EventBus.getDefault().post(event)
  }

  override fun onFileChanged(file: File) {
    // There is no dedicated "content changed on disk" event. Requesting a fresh listing keeps the
    // file tree consistent without duplicating nodes.
    runCatching { EventBus.getDefault().post(ListProjectFilesRequestEvent()) }
  }
}
