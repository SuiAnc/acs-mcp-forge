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

import com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException
import com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
import com.tom.rv2ide.artificial.exceptions.QuotaExceededException
import com.tom.rv2ide.artificial.exceptions.RateLimitException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory

/**
 * HTTP layer for custom providers.
 *
 * Implements the three supported protocols and the two helper requests used by the settings screen
 * ("fetch models" and "test connection"). Non 2xx responses are mapped to the exception types that
 * [com.tom.rv2ide.artificial.agents.AIAgentManager] already understands, so provider auto switching
 * keeps working.
 *
 * @author AndroidCodeStudio
 */
class CustomProviderApi(
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) {

  private val log = LoggerFactory.getLogger(CustomProviderApi::class.java)

  /** Retrieves the model ids advertised by the provider. */
  fun fetchModels(provider: CustomProvider): Result<List<String>> {
    return runCatching {
      val response = getJson(provider, provider.endpointUrl("/models"))
      val data = response.optJSONArray("data") ?: response.optJSONArray("models")
      val models = ArrayList<String>()

      if (data != null) {
        for (index in 0 until data.length()) {
          val item = data.optJSONObject(index)
          val id =
              item?.optString("id")?.takeIf { it.isNotBlank() }
                  ?: item?.optString("name")?.takeIf { it.isNotBlank() }
                  ?: data.optString(index).takeIf { it.isNotBlank() }
          id?.let { models.add(it) }
        }
      }

      if (models.isEmpty()) {
        throw IOException("The provider returned no models (unexpected response shape)")
      }
      models.distinct()
    }
  }

  /**
   * Sends a minimal completion request so the user can verify their configuration.
   *
   * @return the reply text and the elapsed time in milliseconds.
   */
  fun testConnection(provider: CustomProvider): Result<TestResult> {
    val startedAt = System.currentTimeMillis()
    return runCatching {
      val model = effectiveModel(provider)
      val probe = provider.copy(model = model)
      val reply =
          when (provider.protocol) {
            ApiProtocol.CHAT_COMPLETIONS -> chatCompletions(probe, "ping", "Reply with the word pong.", 8)
            ApiProtocol.RESPONSES -> responses(probe, "ping", "Reply with the word pong.", 16)
            ApiProtocol.ASSISTANTS -> assistants(probe, "ping", "Reply with the word pong.")
          }
      TestResult(
          message = "Connected. Reply: ${reply.trim().take(120)}",
          elapsedMs = System.currentTimeMillis() - startedAt,
      )
    }
  }

  /** Runs a completion with the protocol configured on [provider]. */
  fun complete(
      provider: CustomProvider,
      systemPrompt: String,
      userPrompt: String,
      maxTokens: Int = DEFAULT_MAX_TOKENS,
  ): String {
    val target = provider.copy(model = effectiveModel(provider))
    return when (target.protocol) {
      ApiProtocol.CHAT_COMPLETIONS -> chatCompletions(target, systemPrompt, userPrompt, maxTokens)
      ApiProtocol.RESPONSES -> responses(target, systemPrompt, userPrompt, maxTokens)
      ApiProtocol.ASSISTANTS -> assistants(target, systemPrompt, userPrompt)
    }
  }

  private fun effectiveModel(provider: CustomProvider): String {
    return provider.model.takeIf { it.isNotBlank() }
        ?: provider.models.firstOrNull()
        ?: throw IllegalArgumentException(
            "No model configured for provider '${provider.name}'. Fetch the model list or type one."
        )
  }

  // -----------------------------------------------------------------------------------------------
  // Protocol implementations
  // -----------------------------------------------------------------------------------------------

  private fun chatCompletions(
      provider: CustomProvider,
      systemPrompt: String,
      userPrompt: String,
      maxTokens: Int,
  ): String {
    val messages = JSONArray()
    if (systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
    }
    messages.put(JSONObject().put("role", "user").put("content", userPrompt))

    val body =
        JSONObject()
            .put("model", provider.model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", maxTokens)

    val response = postJson(provider, provider.endpointUrl(), body)
    val choices = response.optJSONArray("choices")
    val message = choices?.optJSONObject(0)?.optJSONObject("message")
    val content = message?.optString("content")
    if (content.isNullOrBlank()) {
      throw IOException("Empty response from the provider: ${response.toString().take(500)}")
    }
    return content
  }

  private fun responses(
      provider: CustomProvider,
      systemPrompt: String,
      userPrompt: String,
      maxTokens: Int,
  ): String {
    val input = JSONArray()
    if (systemPrompt.isNotBlank()) {
      input.put(
          JSONObject()
              .put("role", "system")
              .put(
                  "content",
                  JSONArray().put(JSONObject().put("type", "input_text").put("text", systemPrompt)),
              ),
      )
    }
    input.put(
        JSONObject()
            .put("role", "user")
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "input_text").put("text", userPrompt)),
            ),
    )

    val body =
        JSONObject()
            .put("model", provider.model)
            .put("input", input)
            .put("max_output_tokens", maxTokens)

    val response = postJson(provider, provider.endpointUrl(), body)

    response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }

    val output = response.optJSONArray("output")
    val builder = StringBuilder()
    if (output != null) {
      for (index in 0 until output.length()) {
        val item = output.optJSONObject(index) ?: continue
        if (item.optString("type") != "message") continue
        val content = item.optJSONArray("content") ?: continue
        for (contentIndex in 0 until content.length()) {
          val part = content.optJSONObject(contentIndex) ?: continue
          val text = part.optString("text")
          if (text.isNotBlank()) builder.append(text)
        }
      }
    }

    if (builder.isEmpty()) {
      throw IOException("Empty response from the provider: ${response.toString().take(500)}")
    }
    return builder.toString()
  }

  private fun assistants(provider: CustomProvider, systemPrompt: String, userPrompt: String): String {
    val threadsUrl = provider.endpointUrl()
    val thread = postJson(provider, threadsUrl, JSONObject())
    val threadId = thread.optString("id").takeIf { it.isNotBlank() }
        ?: throw IOException("The provider did not return a thread id: ${thread.toString().take(300)}")

    postJson(
        provider,
        "$threadsUrl/$threadId/messages",
        JSONObject().put("role", "user").put("content", userPrompt),
    )

    val runBody =
        JSONObject().put("instructions", systemPrompt).put("model", provider.model)
    if (provider.model.startsWith("asst_")) {
      runBody.put("assistant_id", provider.model)
    }
    val run = postJson(provider, "$threadsUrl/$threadId/runs", runBody)
    val runId = run.optString("id").takeIf { it.isNotBlank() }
        ?: throw IOException("The provider did not return a run id: ${run.toString().take(300)}")

    var status = run.optString("status")
    var polls = 0
    while (status in setOf("queued", "in_progress", "cancelling") && polls < MAX_RUN_POLLS) {
      Thread.sleep(RUN_POLL_INTERVAL_MS)
      polls++
      val state = getJson(provider, "$threadsUrl/$threadId/runs/$runId")
      status = state.optString("status")
    }

    if (status != "completed") {
      throw IOException("The assistant run did not complete (status=$status)")
    }

    val messages = getJson(provider, "$threadsUrl/$threadId/messages?order=desc&limit=10")
    val data = messages.optJSONArray("data")
    if (data != null) {
      for (index in 0 until data.length()) {
        val message = data.optJSONObject(index) ?: continue
        if (message.optString("role") != "assistant") continue
        val content = message.optJSONArray("content")
        if (content != null && content.length() > 0) {
          val text = content.optJSONObject(0)?.optJSONObject("text")?.optString("value")
          if (!text.isNullOrBlank()) return text
        }
      }
    }

    throw IOException("The assistant returned no text content")
  }

  // -----------------------------------------------------------------------------------------------
  // HTTP helpers
  // -----------------------------------------------------------------------------------------------

  private fun getJson(provider: CustomProvider, url: String): JSONObject {
    return execute("GET", provider, url, null)
  }

  private fun postJson(provider: CustomProvider, url: String, body: JSONObject): JSONObject {
    return execute("POST", provider, url, body)
  }

  private fun execute(
      method: String,
      provider: CustomProvider,
      url: String,
      body: JSONObject?,
  ): JSONObject {
    log.debug("{} {}", method, url)
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Content-Type", "application/json")
      if (provider.apiKey.isNotBlank()) {
        connection.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
      }
      if (provider.protocol == ApiProtocol.ASSISTANTS) {
        connection.setRequestProperty("OpenAI-Beta", "assistants=v2")
      }

      if (body != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      }

      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() } ?: ""

      if (code !in 200..299) {
        throw mapHttpError(code, text)
      }

      if (text.isBlank()) {
        return JSONObject()
      }
      return JSONObject(text)
    } finally {
      runCatching { connection.disconnect() }
    }
  }

  private fun mapHttpError(code: Int, body: String): Exception {
    val summary = "HTTP $code: ${body.take(500)}"
    return when {
      code == 401 -> InvalidApiKeyException(summary)
      code == 403 -> InvalidApiKeyException(summary)
      code == 402 -> InsufficientBalanceException(summary)
      code == 429 -> RateLimitException(summary)
      body.contains("insufficient_quota", ignoreCase = true) -> QuotaExceededException(summary)
      else -> IOException(summary)
    }
  }

  /** Result of a connectivity test. */
  data class TestResult(val message: String, val elapsedMs: Long)

  companion object {

    private const val DEFAULT_TIMEOUT_MS = 30_000
    private const val DEFAULT_MAX_TOKENS = 4096
    private const val MAX_RUN_POLLS = 60
    private const val RUN_POLL_INTERVAL_MS = 1000L
  }
}
