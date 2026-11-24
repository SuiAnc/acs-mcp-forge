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
import java.io.IOException

/** Thrown when a request tries to access a path outside of the allowed roots. */
class SandboxViolationException(message: String) : Exception(message)

/**
 * Restricts every filesystem operation performed by the MCP server to a set of allowed roots.
 *
 * Paths are canonicalized before the check so that `..` sequences and symlinks cannot escape the
 * sandbox. This file intentionally has no Android dependencies.
 *
 * @author AndroidCodeStudio
 */
class PathSandbox(roots: Collection<File>) {

  private val allowedRoots: List<String> =
      roots
          .mapNotNull { root ->
            try {
              val canonical = root.canonicalFile
              if (canonical.isDirectory) canonical.path else null
            } catch (e: IOException) {
              null
            }
          }
          .map { it.trimEnd(File.separatorChar) }
          .filter { it.isNotEmpty() }
          .distinct()

  val rootDirectories: List<File>
    get() = allowedRoots.map { File(it) }

  val isEmpty: Boolean
    get() = allowedRoots.isEmpty()

  /**
   * Resolves [rawPath] into a canonical [File] inside the sandbox.
   *
   * @param rawPath the path to resolve; absolute paths are accepted only when they are inside one
   *   of the allowed roots, relative paths are resolved against [PathSandbox.rootDirectories][0].
   * @param mustExist whether the path is required to exist.
   * @param mustBeDirectory whether the path is required to be a directory.
   */
  fun resolve(
      rawPath: String,
      mustExist: Boolean = false,
      mustBeDirectory: Boolean = false,
  ): File {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty()) {
      throw SandboxViolationException("Path must not be empty")
    }
    if (trimmed.indexOf('\u0000') >= 0) {
      throw SandboxViolationException("Path contains an illegal character")
    }

    val candidate =
        if (File(trimmed).isAbsolute) File(trimmed) else File(defaultRoot(), trimmed)

    val canonical =
        try {
          candidate.canonicalFile
        } catch (e: IOException) {
          throw SandboxViolationException("Unable to resolve path: ${e.message}")
        }

    assertInside(canonical)

    if (mustExist && !canonical.exists()) {
      throw SandboxViolationException("No such file or directory: ${canonical.path}")
    }
    if (mustBeDirectory && canonical.exists() && !canonical.isDirectory) {
      throw SandboxViolationException("Not a directory: ${canonical.path}")
    }

    return canonical
  }

  /** Returns `true` if the given file is inside the sandbox. */
  fun contains(file: File): Boolean {
    val canonical =
        try {
          file.canonicalFile
        } catch (e: IOException) {
          return false
        }
    return allowedRoots.any { isWithin(canonical.path, it) }
  }

  /** Returns the root that contains the given file, if any. */
  fun rootOf(file: File): File? {
    val canonical =
        try {
          file.canonicalFile
        } catch (e: IOException) {
          return null
        }
    return allowedRoots.firstOrNull { isWithin(canonical.path, it) }?.let { File(it) }
  }

  private fun defaultRoot(): File {
    return rootDirectories.firstOrNull()
        ?: throw SandboxViolationException(
            "No accessible directory. Open a project or grant a directory in the MCP settings."
        )
  }

  private fun assertInside(canonical: File) {
    if (allowedRoots.isEmpty()) {
      throw SandboxViolationException("No accessible directory is configured")
    }

    if (DENIED_PREFIXES.any { isWithin(canonical.path, it) }) {
      throw SandboxViolationException("Access to system path is not allowed: ${canonical.path}")
    }

    if (allowedRoots.none { isWithin(canonical.path, it) }) {
      throw SandboxViolationException(
          "Path is outside of the allowed directories: ${canonical.path}"
      )
    }
  }

  companion object {

    private val DENIED_PREFIXES =
        listOf(
            "/proc",
            "/sys",
            "/dev",
            "/system",
            "/vendor",
            "/apex",
            "/product",
            "/data/system",
            "/data/misc",
            "/data/adb",
        )

    /** Returns `true` when [path] is equal to [root] or is a descendant of it. */
    fun isWithin(path: String, root: String): Boolean {
      val normalizedRoot = root.trimEnd(File.separatorChar)
      if (path == normalizedRoot) return true
      return path.startsWith("$normalizedRoot${File.separator}")
    }
  }
}
