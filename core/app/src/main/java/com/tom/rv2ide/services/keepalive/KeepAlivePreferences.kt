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

package com.tom.rv2ide.services.keepalive

import com.tom.rv2ide.preferences.internal.prefManager

/**
 * Preferences for the keep alive service and its floating window.
 *
 * @author AndroidCodeStudio
 */
object KeepAlivePreferences {

  private const val BASE = "ide.prefs.developerOptions.keepAlive"

  const val KEY_ENABLED = "$BASE.enabled"
  const val KEY_NOTIFICATION = "$BASE.notification"
  const val KEY_FLOATING = "$BASE.floating"
  const val KEY_EDGE_DELAY = "$BASE.edgeDelayMs"
  const val KEY_ALPHA = "$BASE.alphaPercent"
  const val KEY_FLOATING_X = "$BASE.floatingX"
  const val KEY_FLOATING_Y = "$BASE.floatingY"
  const val KEY_FLOATING_EDGE = "$BASE.floatingEdge"

  const val DEFAULT_EDGE_DELAY_MS = 3000
  const val DEFAULT_ALPHA_PERCENT = 60
  const val MIN_EDGE_DELAY_MS = 1000
  const val MAX_EDGE_DELAY_MS = 10000

  /** Master switch. Off by default: the user has to opt in. */
  var enabled: Boolean
    get() = prefManager.getBoolean(KEY_ENABLED, false)
    set(value) {
      prefManager.putBoolean(KEY_ENABLED, value)
    }

  /** Show the persistent notification (a foreground service always needs one, this only controls
   * the channel importance and whether the description is filled in). */
  var notificationEnabled: Boolean
    get() = prefManager.getBoolean(KEY_NOTIFICATION, true)
    set(value) {
      prefManager.putBoolean(KEY_NOTIFICATION, value)
    }

  /** Show the floating overlay button. */
  var floatingEnabled: Boolean
    get() = prefManager.getBoolean(KEY_FLOATING, false)
    set(value) {
      prefManager.putBoolean(KEY_FLOATING, value)
    }

  /** Delay before the floating button snaps to the closest edge. */
  var edgeDelayMs: Int
    get() =
        prefManager
            .getInt(KEY_EDGE_DELAY, DEFAULT_EDGE_DELAY_MS)
            .coerceIn(MIN_EDGE_DELAY_MS, MAX_EDGE_DELAY_MS)
    set(value) {
      prefManager.putInt(KEY_EDGE_DELAY, value.coerceIn(MIN_EDGE_DELAY_MS, MAX_EDGE_DELAY_MS))
    }

  /** Opacity of the floating button, in percent. */
  var alphaPercent: Int
    get() = prefManager.getInt(KEY_ALPHA, DEFAULT_ALPHA_PERCENT).coerceIn(30, 100)
    set(value) {
      prefManager.putInt(KEY_ALPHA, value.coerceIn(30, 100))
    }

  var floatingX: Int
    get() = prefManager.getInt(KEY_FLOATING_X, -1)
    set(value) {
      prefManager.putInt(KEY_FLOATING_X, value)
    }

  var floatingY: Int
    get() = prefManager.getInt(KEY_FLOATING_Y, -1)
    set(value) {
      prefManager.putInt(KEY_FLOATING_Y, value)
    }

  /** `0` = left edge, `1` = right edge. */
  var floatingEdge: Int
    get() = prefManager.getInt(KEY_FLOATING_EDGE, 1)
    set(value) {
      prefManager.putInt(KEY_FLOATING_EDGE, value)
    }

  /** Resets the remembered position (used when the screen configuration changes). */
  fun resetPosition() {
    floatingX = -1
    floatingY = -1
  }
}
