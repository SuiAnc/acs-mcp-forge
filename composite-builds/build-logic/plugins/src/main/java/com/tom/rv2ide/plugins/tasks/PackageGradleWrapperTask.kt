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

package com.tom.rv2ide.plugins.tasks

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Packages the Gradle wrapper shipped with the IDE into `data/common/gradle-wrapper.zip`.
 *
 * Project templates read the archive from the IDE assets
 * (`ToolsManager.getCommonAsset("gradle-wrapper.zip")`), so it has to be part of the APK. Building
 * it from the wrapper checked into this repository keeps it in sync with
 * `gradle/wrapper/gradle-wrapper.jar` instead of committing a binary.
 *
 * The output directory is assigned by the Android Gradle plugin (see `AndroidIDEAssetsPlugin`); only
 * the relative path inside it is controlled here.
 *
 * @author AndroidCodeStudio
 */
abstract class PackageGradleWrapperTask : DefaultTask() {

  /** Root directory of the repository which contains the wrapper files. */
  @get:Internal internal var projectRoot: File? = null

  /** Output directory to write `data/common/gradle-wrapper.zip` to. */
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun packageGradleWrapper() {
    val root =
        projectRoot
            ?: throw GradleException(
                "projectRoot must be set for ${PackageGradleWrapperTask::class.java.simpleName}"
            )

    val commonDir = outputDirectory.dir("data/common").get().asFile.apply { mkdirs() }
    val archive = File(commonDir, "gradle-wrapper.zip")

    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
      for (entry in ENTRIES) {
        val source = File(root, entry)
        if (!source.isFile) {
          throw GradleException("Missing Gradle wrapper file: ${source.path}")
        }

        zip.putNextEntry(ZipEntry(entry).apply { time = FIXED_ENTRY_TIME })
        source.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
      }
    }
  }

  companion object {

    /** Entries expected by `ProjectTemplateBuilder.gradleWrapper()`. */
    private val ENTRIES = arrayOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar")

    /** Fixed timestamp (1980-01-01T00:00:00Z) so that the archive is reproducible. */
    private const val FIXED_ENTRY_TIME = 315532800000L
  }
}
