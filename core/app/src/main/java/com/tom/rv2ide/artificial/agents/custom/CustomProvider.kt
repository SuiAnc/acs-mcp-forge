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

package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.preferences.internal.prefManager
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory

/** Protocol flavours supported by a custom provider. */
enum class ApiProtocol(val id: String, val displayName: String, val defaultPath: String) {

  /** `POST {baseUrl}{apiPath}` with `messages`, the same shape the built in agents use. */
  CHAT_COMPLETIONS("chat_completions", "Chat Completions", "/chat/completions"),

  /** OpenAI Responses API: `POST {baseUrl}{apiPath}` with `input`. */
  RESPONSES("responses", "Responses", "/responses"),

  /** OpenAI Assistants v2: threads + messages + runs. `apiPath` is the threads endpoint. */
  ASSISTANTS("assistants", "Assistants", "/threads");

  companion object {

    fun fromId(id: String?): ApiProtocol {
      return values().firstOrNull { it.id == id } ?: CHAT_COMPLETIONS
    }
  }
}

/**
 * A user defined, OpenAI compatible provider.
 *
 * The API key is stored next to the rest of the configuration so that the behaviour matches the
 * built in providers, which also keep their keys in the shared preferences.
 */
data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val apiPath: String,
    val protocol: ApiProtocol,
    val model: String,
    val models: List<String>,
) {

  /** Registry id used by [com.tom.rv2ide.artificial.agents.AIAgentRegistry]. */
  val providerId: String
    get() = PROVIDER_PREFIX + id

  /** Absolute URL for [path] (defaults to the configured API path). */
  fun endpointUrl(path: String = apiPath): String {
    val base = baseUrl.trim().trimEnd('/')
    val suffix = path.trim().let { if (it.startsWith("/")) it else "/$it" }
    return base + suffix
  }

  fun withModels(newModels: List<String>): CustomProvider = copy(models = newModels)

  fun withModel(newModel: String): CustomProvider = copy(model = newModel)

  fun toJson(): JSONObject {
    return JSONObject().apply {
      put("id", id)
      put("name", name)
      put("baseUrl", baseUrl)
      put("apiKey", apiKey)
      put("apiPath", apiPath)
      put("protocol", protocol.id)
      put("model", model)
      put("models", JSONArray(models))
    }
  }

  companion object {

    const val PROVIDER_PREFIX = "custom:"

    fun fromJson(json: JSONObject): CustomProvider? {
      val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null

      // A provider is persisted as soon as it is created, before the user has filled the base URL,
      // so a blank base URL must not discard the entry. The value is validated when it is used.
      val baseUrl = json.optString("baseUrl").trim()

      val protocol = ApiProtocol.fromId(json.optString("protocol"))
      val modelsJson = json.optJSONArray("models") ?: JSONArray()
      val models = ArrayList<String>()
      for (index in 0 until modelsJson.length()) {
        modelsJson.optString(index).takeIf { it.isNotBlank() }?.let { models.add(it) }
      }

      return CustomProvider(
          id = id,
          name = json.optString("name").ifBlank { "Custom" },
          baseUrl = baseUrl,
          apiKey = json.optString("apiKey"),
          apiPath = json.optString("apiPath").ifBlank { protocol.defaultPath },
          protocol = protocol,
          model = json.optString("model"),
          models = models,
      )
    }

    fun createId(): String = UUID.randomUUID().toString()

    /** A blank provider with the default protocol. */
    fun empty(): CustomProvider {
      return CustomProvider(
          id = createId(),
          name = "",
          baseUrl = "",
          apiKey = "",
          apiPath = ApiProtocol.CHAT_COMPLETIONS.defaultPath,
          protocol = ApiProtocol.CHAT_COMPLETIONS,
          model = "",
          models = emptyList(),
      )
    }
  }
}

/**
 * Persistence for [CustomProvider]s.
 *
 * @author AndroidCodeStudio
 */
object CustomProviders {

  private val log = LoggerFactory.getLogger(CustomProviders::class.java)

  const val KEY_PROVIDERS = "ai_custom_providers"
  const val KEY_CURRENT = "ai_custom_providers_current"

  /** All configured providers, in insertion order. */
  fun all(): List<CustomProvider> {
    val raw = prefManager.getString(KEY_PROVIDERS, "") ?: ""
    if (raw.isBlank()) return emptyList()

    return try {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val obj = array.optJSONObject(index) ?: continue
          CustomProvider.fromJson(obj)?.let { add(it) }
        }
      }
    } catch (e: Exception) {
      log.warn("Unable to parse the custom providers", e)
      emptyList()
    }
  }

  fun byId(id: String): CustomProvider? = all().firstOrNull { it.id == id }

  /** Inserts or replaces [provider]. */
  fun save(provider: CustomProvider) {
    val providers = all().toMutableList()
    val index = providers.indexOfFirst { it.id == provider.id }
    if (index >= 0) {
      providers[index] = provider
    } else {
      providers.add(provider)
    }
    persist(providers)

    val current = currentId()
    if (current.isNullOrBlank() || byId(current) == null) {
      setCurrent(provider.id)
    }
  }

  fun delete(id: String) {
    val providers = all().filterNot { it.id == id }.toMutableList()
    persist(providers)
    if (currentId() == id) {
      val fallback = providers.firstOrNull()
      if (fallback != null) {
        setCurrent(fallback.id)
      } else {
        prefManager.remove(KEY_CURRENT)
      }
    }
  }

  fun setCurrent(id: String) {
    prefManager.putString(KEY_CURRENT, id)
  }

  fun currentId(): String? = prefManager.getString(KEY_CURRENT, "")?.takeIf { it.isNotBlank() }

  fun current(): CustomProvider? = currentId()?.let { byId(it) }

  fun providerIdOf(id: String): String = CustomProvider.PROVIDER_PREFIX + id

  /** Returns the provider id part of a `custom:<uuid>` registry id, or `null`. */
  fun idOfProviderId(providerId: String): String? {
    if (!providerId.startsWith(CustomProvider.PROVIDER_PREFIX)) return null
    return providerId.removePrefix(CustomProvider.PROVIDER_PREFIX).takeIf { it.isNotBlank() }
  }

  private fun persist(providers: List<CustomProvider>) {
    val array = JSONArray()
    providers.forEach { array.put(it.toJson()) }
    prefManager.putString(KEY_PROVIDERS, array.toString())
  }
}
