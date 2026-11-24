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

package com.tom.rv2ide.mcp.security

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append only audit log for tool invocations.
 *
 * Every tool call is recorded together with its duration, so that the user can review what an MCP
 * client did. The file is rotated once it grows beyond [maxBytes], keeping a single `.1` backup.
 *
 * This file intentionally has no Android dependencies.
 *
 * @author AndroidCodeStudio
 */
class AuditLog(private val file: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

  private val lock = Any()
  private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

  /** Appends a single line to the audit log. */
  fun append(message: String) {
    val line = "${timestamp.format(Date())} $message"
    synchronized(lock) {
      try {
        file.parentFile?.mkdirs()
        rotateIfNeeded()
        file.appendText(line + "\n")
      } catch (_: Exception) {
        // Auditing must never break a tool call.
      }
    }
  }

  /** Returns the last [lines] entries, newest last. */
  fun tail(lines: Int): List<String> {
    synchronized(lock) {
      if (!file.exists()) return emptyList()
      return try {
        val all = file.readLines()
        if (lines <= 0) all else all.takeLast(lines)
      } catch (_: Exception) {
        emptyList()
      }
    }
  }

  /** Clears the audit log. */
  fun clear() {
    synchronized(lock) {
      runCatching { file.delete() }
      runCatching { File(file.path + ".1").delete() }
    }
  }

  /** Returns the current size of the audit log in bytes. */
  fun size(): Long = runCatching { file.length() }.getOrDefault(0L)

  private fun rotateIfNeeded() {
    if (!file.exists() || file.length() < maxBytes) return
    val backup = File(file.path + ".1")
    runCatching { backup.delete() }
    runCatching { file.renameTo(backup) }
  }

  companion object {

    /** Default maximum size before the log is rotated (1 MiB). */
    const val DEFAULT_MAX_BYTES = 1024L * 1024L
  }
}
