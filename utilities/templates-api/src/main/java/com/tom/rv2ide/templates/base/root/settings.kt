/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.base.root

import com.tom.rv2ide.templates.base.ProjectTemplateBuilder

internal fun ProjectTemplateBuilder.settingsGradleSrcStr(): String {
  return """
pluginManagement {
  repositories {
    // repo.maven.apache.org is unreachable on some mobile networks, so the official Maven
    // Central alias and Google's Central mirror are tried first.
    maven { url = uri("https://repo1.maven.org/maven2") }
    maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven { url = uri("https://repo1.maven.org/maven2") }
    maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
    google()
    mavenCentral()
  }
}

rootProject.name = "${data.name}"

${modules.joinToString(separator = ", ") { "include(\"${it.name}\")" }}    
  """
      .trim()
}
