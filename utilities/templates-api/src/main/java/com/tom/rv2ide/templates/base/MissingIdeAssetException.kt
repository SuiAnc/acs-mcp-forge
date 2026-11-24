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

package com.tom.rv2ide.templates.base

/**
 * Thrown when an asset shipped with the IDE is missing or incomplete.
 *
 * Project templates read a few assets which are packaged into the APK (for example the Gradle
 * wrapper). When such an asset is absent the recipe would otherwise fail with an unrelated
 * [RuntimeException], which the MCP tools can only report as an opaque stack trace.
 *
 * @param assetPath asset path as used with `AssetManager.open`, for example
 *   `data/common/gradle-wrapper.zip`
 * @author AndroidCodeStudio
 */
class MissingIdeAssetException(val assetPath: String) :
    RuntimeException("Missing or incomplete IDE asset: $assetPath")
