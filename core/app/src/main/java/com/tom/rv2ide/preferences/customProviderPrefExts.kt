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

package com.tom.rv2ide.preferences

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.artificial.agents.custom.ApiProtocol
import com.tom.rv2ide.artificial.agents.custom.CustomAgent
import com.tom.rv2ide.artificial.agents.custom.CustomProvider
import com.tom.rv2ide.artificial.agents.custom.CustomProviderApi
import com.tom.rv2ide.artificial.agents.custom.CustomProviders
import com.tom.rv2ide.fragments.IDEPreferencesFragment
import com.tom.rv2ide.resources.R.string
import kotlinx.parcelize.Parcelize

/**
 * Settings for user defined AI providers.
 *
 * All action items extend [IPreference] (and not [SimpleClickablePreference]) and build their click
 * listener inside [onCreateView]: that way the behaviour survives the preference tree being
 * parcelled and restored (for example on a configuration change).
 *
 * @author AndroidCodeStudio
 */

private enum class ProviderField {
  NAME,
  BASE_URL,
  API_KEY,
  API_PATH,
  MODEL,
}

/** Lists the configured providers and offers to create new ones. */
@Parcelize
internal class CustomProvidersScreen(
    override val key: String = "idepref_ai_custom_providers",
    override val title: Int = string.ai_custom_providers_title,
    override val summary: Int? = string.ai_custom_providers_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    rebuild()
  }

  /**
   * Rebuilds [children] from the providers which are currently stored.
   *
   * The list must not be a snapshot taken when this screen was parcelled: providers can be added or
   * removed while the enclosing fragment is on the back stack, and the list has to show the current
   * state when it is shown again.
   */
  fun rebuild(): List<IPreference> {
    val mutable = children as MutableList
    mutable.clear()
    CustomProviders.all().forEach { provider ->
      mutable.add(CustomProviderEntryScreen(providerId = provider.id))
    }
    mutable.add(AddCustomProviderPreference())
    return children
  }
}

/** Editor for a single provider. */
@Parcelize
internal class CustomProviderEntryScreen(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_$providerId",
    override val title: Int = string.ai_custom_provider_edit_title,
    override val summary: Int? = null,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(CustomProviderTextField(providerId, ProviderField.NAME))
    addPreference(CustomProviderTextField(providerId, ProviderField.BASE_URL))
    addPreference(CustomProviderTextField(providerId, ProviderField.API_KEY))
    addPreference(CustomProviderTextField(providerId, ProviderField.API_PATH))
    addPreference(CustomProviderTextField(providerId, ProviderField.MODEL))
    addPreference(CustomProviderProtocolPreference(providerId))
    addPreference(CustomProviderFetchModelsPreference(providerId))
    addPreference(CustomProviderTestPreference(providerId))
    addPreference(CustomProviderSetCurrentPreference(providerId))
    addPreference(CustomProviderDeletePreference(providerId))
  }

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key

    // Read the provider when the row is rendered instead of capturing it at construction time, so
    // that renames and model changes are visible as soon as the list is refreshed.
    val provider = CustomProviders.byId(providerId)
    if (provider == null) {
      preference.title = context.getString(title)
      preference.summary = context.getString(string.ai_custom_provider_missing)
      preference.isEnabled = false
      return preference
    }

    preference.title = provider.name.ifBlank { context.getString(title) }
    preference.summary =
        "${provider.protocol.displayName} · " +
            provider.baseUrl.ifBlank {
              context.getString(string.ai_custom_provider_base_url_empty)
            } +
            " · " + provider.model.ifBlank { "no model" }
    return preference
  }
}

@Parcelize
internal class AddCustomProviderPreference(
    override val key: String = "idepref_ai_custom_provider_add",
    override val title: Int = string.ai_custom_provider_add,
    override val summary: Int? = string.ai_custom_provider_add_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_add_summary)
    preference.setOnPreferenceClickListener {
      val provider = CustomProvider.empty().copy(name = "Custom ${CustomProviders.all().size + 1}")
      CustomProviders.save(provider)
      CustomAgent.registerAgent(context.applicationContext)

      // Open the editor for the new provider right away. The previous implementation only showed a
      // toast and called Activity.recreate(), which never navigated and kept rendering the stale
      // list snapshot.
      IDEPreferencesFragment.current(context)
          ?.reload(CustomProviderEntryScreen(provider.id).children, pushToBackStack = true)
      Toast.makeText(context, string.ai_custom_provider_created, Toast.LENGTH_SHORT).show()
      true
    }
    return preference
  }
}

@Parcelize
private class CustomProviderTextField(
    private val providerId: String,
    private val field: ProviderField,
    override val key: String = "idepref_ai_custom_provider_${providerId}_${field.name}",
    override val title: Int =
        when (field) {
          ProviderField.NAME -> string.ai_custom_provider_name
          ProviderField.BASE_URL -> string.ai_custom_provider_base_url
          ProviderField.API_KEY -> string.ai_custom_provider_api_key
          ProviderField.API_PATH -> string.ai_custom_provider_api_path
          ProviderField.MODEL -> string.ai_custom_provider_model
        },
    override val summary: Int? =
        when (field) {
          ProviderField.NAME -> string.ai_custom_provider_name_summary
          ProviderField.BASE_URL -> string.ai_custom_provider_base_url_summary
          ProviderField.API_KEY -> string.ai_custom_provider_api_key_summary
          ProviderField.API_PATH -> string.ai_custom_provider_api_path_summary
          ProviderField.MODEL -> string.ai_custom_provider_model_summary
        },
) : EditTextPreference() {

  override fun onConfigureTextInput(input: TextInputLayout) {
    val provider = CustomProviders.byId(providerId) ?: return
    val value =
        when (field) {
          ProviderField.NAME -> provider.name
          ProviderField.BASE_URL -> provider.baseUrl
          ProviderField.API_KEY -> provider.apiKey
          ProviderField.API_PATH -> provider.apiPath
          ProviderField.MODEL -> provider.model
        }
    if (field == ProviderField.API_KEY) {
      input.editText?.inputType =
          InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    input.editText?.setText(value)
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    val provider = CustomProviders.byId(providerId) ?: return false
    val text = (newValue as? String).orEmpty().trim()
    val updated =
        when (field) {
          ProviderField.NAME -> provider.copy(name = text.ifBlank { "Custom" })
          ProviderField.BASE_URL -> provider.copy(baseUrl = text.trimEnd('/'))
          ProviderField.API_KEY -> provider.copy(apiKey = text)
          ProviderField.API_PATH ->
              provider.copy(
                  apiPath = (text.ifBlank { provider.protocol.defaultPath }).let {
                    if (it.startsWith("/")) it else "/$it"
                  }
              )
          ProviderField.MODEL -> provider.copy(model = text)
        }
    CustomProviders.save(updated)
    CustomAgent.registerAgent(preference.context.applicationContext)
    return true
  }
}

@Parcelize
internal class CustomProviderProtocolPreference(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_${providerId}_protocol",
    override val title: Int = string.ai_custom_provider_protocol,
    override val summary: Int? = string.ai_custom_provider_protocol_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_protocol_summary)
    preference.setOnPreferenceClickListener {
      val provider = CustomProviders.byId(providerId)
      if (provider == null) {
        false
      } else {
        val protocols = ApiProtocol.values()
        val labels = protocols.map { it.displayName }.toTypedArray()
        val checked = protocols.indexOf(provider.protocol)
        MaterialAlertDialogBuilder(context)
            .setTitle(string.ai_custom_provider_protocol)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
              val selected = protocols[which]
              val pathWasDefault = provider.apiPath == provider.protocol.defaultPath
              CustomProviders.save(
                  provider.copy(
                      protocol = selected,
                      apiPath = if (pathWasDefault) selected.defaultPath else provider.apiPath,
                  )
              )
              CustomAgent.registerAgent(context.applicationContext)
              dialog.dismiss()
              Toast.makeText(context, selected.displayName, Toast.LENGTH_SHORT).show()
              IDEPreferencesFragment.current(context)?.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        true
      }
    }
    return preference
  }
}

@Parcelize
internal class CustomProviderFetchModelsPreference(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_${providerId}_models",
    override val title: Int = string.ai_custom_provider_fetch_models,
    override val summary: Int? = string.ai_custom_provider_fetch_models_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_fetch_models_summary)
    preference.setOnPreferenceClickListener {
      val provider = CustomProviders.byId(providerId)
      if (provider == null) {
        false
      } else if (!ensureBaseUrl(context, provider)) {
        true
      } else {
        runAsync(context, string.ai_custom_provider_working) {
          CustomProviderApi().fetchModels(provider).fold(
              onSuccess = { models ->
                CustomProviders.save(provider.withModels(models))
                CustomAgent.registerAgent(context.applicationContext)
                onMain { showModelPicker(context, providerId, models) }
              },
              onFailure = { error -> onMain { showError(context, error) } },
          )
        }
        true
      }
    }
    return preference
  }
}

@Parcelize
internal class CustomProviderTestPreference(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_${providerId}_test",
    override val title: Int = string.ai_custom_provider_test,
    override val summary: Int? = string.ai_custom_provider_test_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_test_summary)
    preference.setOnPreferenceClickListener {
      val provider = CustomProviders.byId(providerId)
      if (provider == null) {
        false
      } else if (!ensureBaseUrl(context, provider)) {
        true
      } else {
        runAsync(context, string.ai_custom_provider_working) {
          CustomProviderApi().testConnection(provider).fold(
              onSuccess = { test ->
                onMain {
                  MaterialAlertDialogBuilder(context)
                      .setTitle(string.ai_custom_provider_test)
                      .setMessage(
                          context.getString(
                              string.ai_custom_provider_test_ok,
                              test.elapsedMs.toInt(),
                              test.message,
                          )
                      )
                      .setPositiveButton(android.R.string.ok, null)
                      .show()
                }
              },
              onFailure = { error -> onMain { showError(context, error) } },
          )
        }
        true
      }
    }
    return preference
  }
}

@Parcelize
internal class CustomProviderSetCurrentPreference(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_${providerId}_current",
    override val title: Int = string.ai_custom_provider_set_current,
    override val summary: Int? = string.ai_custom_provider_set_current_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_set_current_summary)
    preference.setOnPreferenceClickListener {
      CustomProviders.setCurrent(providerId)
      Toast.makeText(context, string.ai_custom_provider_current_set, Toast.LENGTH_SHORT).show()
      true
    }
    return preference
  }
}

@Parcelize
internal class CustomProviderDeletePreference(
    private val providerId: String,
    override val key: String = "idepref_ai_custom_provider_${providerId}_delete",
    override val title: Int = string.ai_custom_provider_delete,
    override val summary: Int? = string.ai_custom_provider_delete_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.ai_custom_provider_delete_summary)
    preference.setOnPreferenceClickListener {
      MaterialAlertDialogBuilder(context)
          .setTitle(string.ai_custom_provider_delete)
          .setMessage(string.ai_custom_provider_delete_confirm)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok) { _, _ ->
            CustomProviders.delete(providerId)
            CustomAgent.unregister(CustomProvider.PROVIDER_PREFIX + providerId)
            CustomAgent.registerAgent(context.applicationContext)
            // Land on a freshly built list so that the deleted entry disappears immediately.
            IDEPreferencesFragment.current(context)
                ?.reload(customProviderListChildren(), pushToBackStack = false)
            Toast.makeText(context, string.ai_custom_provider_deleted, Toast.LENGTH_SHORT).show()
          }
          .show()
      true
    }
    return preference
  }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private fun onMain(block: () -> Unit) {
  Handler(Looper.getMainLooper()).post(block)
}

private fun runAsync(context: Context, messageRes: Int, block: () -> Unit) {
  Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
  Thread(block, "custom-provider-task").apply { isDaemon = true }.start()
}

/** Children of the provider list, rebuilt from the providers which are stored right now. */
private fun customProviderListChildren(): List<IPreference> = CustomProvidersScreen().rebuild()

/** Shows a readable error when the provider does not have a base URL yet. */
private fun ensureBaseUrl(context: Context, provider: CustomProvider): Boolean {
  if (provider.baseUrl.isNotBlank()) return true

  MaterialAlertDialogBuilder(context)
      .setTitle(string.ai_custom_provider_error)
      .setMessage(string.ai_custom_provider_base_url_required)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  return false
}

private fun showError(context: Context, error: Throwable) {
  MaterialAlertDialogBuilder(context)
      .setTitle(string.ai_custom_provider_error)
      .setMessage("${error::class.java.simpleName}: ${error.message ?: "unknown error"}")
      .setPositiveButton(android.R.string.ok, null)
      .show()
}

private fun showModelPicker(context: Context, providerId: String, models: List<String>) {
  val limited = models.take(200)
  MaterialAlertDialogBuilder(context)
      .setTitle(string.ai_custom_provider_pick_model)
      .setItems(limited.toTypedArray()) { _, which ->
        val provider = CustomProviders.byId(providerId) ?: return@setItems
        CustomProviders.save(provider.withModel(limited[which]))
        CustomAgent.registerAgent(context.applicationContext)
        Toast.makeText(context, limited[which], Toast.LENGTH_SHORT).show()
        IDEPreferencesFragment.current(context)?.refresh()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
}
