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

package com.tom.rv2ide.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.preferences.IPreference
import com.tom.rv2ide.preferences.IPreferenceGroup
import com.tom.rv2ide.preferences.IPreferenceScreen
import com.tom.rv2ide.preferences.observers.LSPStateObserver

class IDEPreferencesFragment : BasePreferenceFragment() {

  private var children: List<IPreference> = emptyList()

  private val serverStateListener = {
    refreshPreferences()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply {
        duration = 600
    }
    returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply {
        duration = 600
    }
    exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply {
        duration = 600
    }
    reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply {
        duration = 600
    }
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    if (context == null) {
      return
    }

    @Suppress("DEPRECATION")
    this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  override fun onResume() {
    super.onResume()
    LSPStateObserver.addListener(serverStateListener)
    // Re-render the current screen so that rows and their summaries reflect the latest stored
    // values when the user comes back from a sub screen (for example after editing a provider).
    refreshPreferences()
  }

  override fun onPause() {
    super.onPause()
    LSPStateObserver.removeListener(serverStateListener)
  }

  private fun refreshPreferences() {
    if (context == null) {
      return
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  /** Re-renders the current preference screen from its children. */
  fun refresh() = refreshPreferences()

  /**
   * Replaces this fragment with a new one showing [children].
   *
   * Needed when the set of rows changes (a provider was added or removed): the current screen was
   * built from a snapshot taken when it was created and cannot be refreshed in place.
   */
  fun reload(children: List<IPreference>, pushToBackStack: Boolean) {
    val replacement =
        IDEPreferencesFragment().apply {
          arguments =
              Bundle().apply { putParcelableArrayList(EXTRA_CHILDREN, ArrayList(children)) }
        }

    parentFragmentManager
        .beginTransaction()
        .replace(this.id, replacement)
        .apply { if (pushToBackStack) addToBackStack(null) }
        .commit()
  }

  private fun addChildren(children: List<IPreference>, pref: PreferenceGroup) {
    for (child in children) {
      val preference = child.onCreateView(requireContext())
      if (child is IPreferenceScreen) {
        preference.fragment = IDEPreferencesFragment::class.java.name
        preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))
        pref.addPreference(preference)
        continue
      }

      if (child is IPreferenceGroup) {
        pref.addPreference(preference as PreferenceCategory)
        addChildren(child.children, preference)
        continue
      }

      pref.addPreference(preference)
    }
  }

  companion object {
    const val EXTRA_CHILDREN = "ide.preferences.fragment.children"

    /** Returns the preference fragment which is currently visible in [context], if any. */
    fun current(context: Context): IDEPreferencesFragment? {
      val activity = context as? FragmentActivity ?: return null
      return activity.supportFragmentManager.fragments
          .filterIsInstance<IDEPreferencesFragment>()
          .lastOrNull { it.isAdded && !it.isHidden }
    }
  }
}