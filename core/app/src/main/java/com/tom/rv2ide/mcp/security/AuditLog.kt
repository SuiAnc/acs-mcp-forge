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

  /**
   * Appends a structured entry.
   *
   * The on disk format is a tab separated line:
   * `<epochMillis>\t<op>\t<tool>\t<durationMs>\t<error>\t<summary>`
   *
   * Tab separated values keep this class free of Android/JSON dependencies while still being easy
   * to parse for the audit log screen and to copy as TSV.
   */
  fun appendEntry(
      timeMillis: Long,
      tool: String,
      op: String,
      durationMs: Long,
      isError: Boolean,
      summary: String = "",
  ) {
    val line =
        buildString {
          append(timeMillis).append('\t')
          append(sanitize(op)).append('\t')
          append(sanitize(tool)).append('\t')
          append(durationMs).append('\t')
          append(isError).append('\t')
          append(sanitize(summary))
        }
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

  /**
   * Returns the most recent [limit] entries, newest last.
   *
   * Plain text lines written by older builds are parsed on a best effort basis so existing logs
   * stay readable after the upgrade.
   */
  fun entries(limit: Int = DEFAULT_ENTRY_LIMIT): List<AuditEntry> {
    val lines = tail(limit)
    return lines.mapNotNull { parse(it) }
  }

  private fun parse(line: String): AuditEntry? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed[0].isDigit() && trimmed.contains('\t')) {
      val parts = trimmed.split('\t', limit = 6)
      if (parts.size >= 5) {
        val time = parts[0].toLongOrNull() ?: return null
        return AuditEntry(
            timeMillis = time,
            timeText = timestamp.format(Date(time)),
            op = parts[1],
            tool = parts[2],
            durationMs = parts[3].toLongOrNull() ?: 0L,
            isError = parts[4].equals("true", ignoreCase = true),
            summary = parts.getOrElse(5) { "" },
        )
      }
    }

    // Legacy format: "2026-11-24 23:45:55.844 tools/call name=file_read durationMs=3 isError=false args=..."
    val match = LEGACY_PATTERN.find(trimmed) ?: return null
    val groups = match.groupValues
    val time =
        runCatching { timestamp.parse(groups[1])?.time }.getOrNull() ?: System.currentTimeMillis()
    return AuditEntry(
        timeMillis = time,
        timeText = groups[1],
        op = "",
        tool = groups[2],
        durationMs = groups[3].toLongOrNull() ?: 0L,
        isError = groups[4].equals("true", ignoreCase = true),
        summary = groups.getOrElse(5) { "" }.trim(),
    )
  }

  private fun sanitize(value: String): String {
    return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
  }

  /** Appends a single line to the audit log (legacy plain text format). */
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

  /** A single audit log entry. */
  data class AuditEntry(
      val timeMillis: Long,
      val timeText: String,
      val op: String,
      val tool: String,
      val durationMs: Long,
      val isError: Boolean,
      val summary: String,
  )

  companion object {

    /** Default maximum size before the log is rotated (1 MiB). */
    const val DEFAULT_MAX_BYTES = 1024L * 1024L

    /** Number of entries returned by [entries] when no limit is given. */
    const val DEFAULT_ENTRY_LIMIT = 100

    private val LEGACY_PATTERN =
        Regex(
            """(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+tools/call\s+name=(\S+)\s+durationMs=(\d+)\s+isError=(\w+)(.*)"""
        )
  }
}
