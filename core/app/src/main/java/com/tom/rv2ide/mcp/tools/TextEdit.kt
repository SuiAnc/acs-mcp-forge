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

/**
 * Exact-match text replacement used by the `file_edit` tool.
 *
 * The replacement is only applied when the search text matches exactly once, unless [replaceAll]
 * is requested. This prevents accidental edits in the middle of a large file.
 *
 * @author AndroidCodeStudio
 */
object TextEdit {

  sealed class Result {

    class Success(val content: String, val occurrences: Int) : Result()

    class Failure(val reason: String) : Result()
  }

  fun replace(content: String, oldText: String, newText: String, replaceAll: Boolean): Result {
    if (oldText.isEmpty()) {
      return Result.Failure("Argument 'oldText' must not be empty")
    }
    if (oldText == newText) {
      return Result.Failure("Argument 'newText' is identical to 'oldText'")
    }

    val occurrences = countOccurrences(content, oldText)
    if (occurrences == 0) {
      return Result.Failure(
          "'oldText' was not found. Read the file again and use a snippet that exists verbatim."
      )
    }
    if (occurrences > 1 && !replaceAll) {
      return Result.Failure(
          "'oldText' matches $occurrences times. Provide a longer, unique snippet or pass " +
              "replaceAll=true."
      )
    }

    val updated =
        if (replaceAll) {
          content.replace(oldText, newText)
        } else {
          content.replaceFirst(oldText, newText)
        }

    return Result.Success(updated, occurrences)
  }

  fun countOccurrences(content: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = content.indexOf(needle)
    while (index >= 0) {
      count++
      index = content.indexOf(needle, index + needle.length)
    }
    return count
  }
}
