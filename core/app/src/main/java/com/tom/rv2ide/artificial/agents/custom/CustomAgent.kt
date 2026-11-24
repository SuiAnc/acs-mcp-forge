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

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * [AIAgent] implementation backed by a user defined provider.
 *
 * The prompt assembly, history handling and retry semantics intentionally mirror
 * `artificial/agents/openai/OpenAI.kt`, so switching to a custom provider does not change the
 * behaviour the user is used to. Parsing the response and writing files stays in
 * [com.tom.rv2ide.artificial.agents.AIAgentManager], exactly like the built in agents.
 *
 * @author AndroidCodeStudio
 */
class CustomAgent(private val customId: String) : AIAgent {

  private val log = LoggerFactory.getLogger(CustomAgent::class.java)

  private var provider: CustomProvider? = CustomProviders.byId(customId)
  private var apiKey: String? = null
  private var appContext: Context? = null
  private var fileWriter: AIFileWriter? = null
  private var projectTreeResult: ProjectTreeResult? = null

  private val writingRules = WritingRules.Instructions()
  private val api = CustomProviderApi()
  private val conversationHistory = mutableListOf<ConversationMessage>()
  private val modificationHistory = mutableListOf<ModificationAttempt>()

  private var currentAttemptCount = 0
  private val maxRetryAttempts = 3

  override val providerId: String
    get() = CustomProvider.PROVIDER_PREFIX + customId

  override val providerName: String
    get() = provider?.name?.takeIf { it.isNotBlank() } ?: "Custom"

  override fun initialize(apiKey: String, context: Context) {
    appContext = context.applicationContext
    this.apiKey = apiKey
    this.provider = CustomProviders.byId(customId)
    this.fileWriter = AIFileWriter(context)
  }

  override fun reinitializeWithNewModel(apiKey: String, context: Context) {
    initialize(apiKey, context)
  }

  override fun setContext(context: Context) {
    appContext = context.applicationContext
    fileWriter = AIFileWriter(context)
  }

  override fun setProjectData(projectTreeResult: ProjectTreeResult) {
    this.projectTreeResult = projectTreeResult
  }

  override fun clearConversation() {
    conversationHistory.clear()
    currentAttemptCount = 0
  }

  override fun isInitialized(): Boolean {
    val current = provider ?: return false
    return current.baseUrl.isNotBlank() &&
        current.endpointUrl().isNotBlank() &&
        (current.model.isNotBlank() || current.models.isNotEmpty())
  }

  override suspend fun generateCode(
      prompt: String,
      context: String?,
      language: String,
      projectStructure: String?,
  ): Result<String> = withContext(Dispatchers.IO) {
    try {
      val current = provider ?: return@withContext Result.failure(
          IllegalStateException("Custom provider is not configured")
      )
      if (!isInitialized()) {
        return@withContext Result.failure(
            IllegalStateException("Custom provider '${current.name}' has no model configured")
        )
      }

      val fileContents = readRelevantFiles()
      val needsCorrection = isUserRequestingCorrection(prompt)

      val fullPrompt = buildString {
        append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
        if (projectTreeResult != null) {
          append(projectTreeResult!!.tree)
          append("\n\n")
          append(
              "CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like " +
                  "'/storage/emulated/0/project' or 'com.example.yourproject'.\n"
          )
          append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
        }

        if (fileContents.isNotEmpty()) {
          append("=== CURRENT FILES CONTENT ===\n")
          fileContents.forEach { (path, content) ->
            append("FILE: $path\n")
            append("CONTENT:\n")
            append(content)
            append("\n\n")
          }
        }

        if (context != null) {
          append("=== ADDITIONAL CONTEXT ===\n")
          append(context)
          append("\n\n")
        }

        if (conversationHistory.isNotEmpty()) {
          append("=== CONVERSATION HISTORY ===\n")
          conversationHistory.forEach { message ->
            append("${message.role.uppercase()}: ${message.content}\n\n")
          }
        }

        if (needsCorrection && modificationHistory.isNotEmpty()) {
          append("=== CORRECTION REQUIRED ===\n")
          append("The user indicated the previous modification was WRONG.\n")
          append("Previous failed attempts:\n")
          modificationHistory.takeLast(3).forEach { attempt ->
            append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
            append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
          }
          append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
          append("Analyze what went wrong and provide a better solution.\n\n")
        }

        if (currentAttemptCount > 0) {
          append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
          append("This is retry attempt number $currentAttemptCount.\n")
          append("Previous attempts did not satisfy the user.\n")
          append("Think carefully and provide a different solution.\n\n")
        }

        append("=== USER REQUEST ===\n")
        append(prompt)
      }

      val response = api.complete(current.copy(apiKey = apiKey ?: current.apiKey), writingRules.useThis(), fullPrompt)
      if (response.isBlank()) {
        return@withContext Result.failure(Exception("Empty response from AI"))
      }

      conversationHistory.add(ConversationMessage("user", prompt))
      conversationHistory.add(ConversationMessage("assistant", response))
      while (conversationHistory.size > 20) {
        conversationHistory.removeAt(0)
      }

      Result.success(response)
    } catch (e: Exception) {
      log.warn("Custom provider request failed", e)
      Result.failure(e)
    }
  }

  override fun recordModification(
      filePath: String,
      oldContent: String?,
      newContent: String,
      success: Boolean,
  ) {
    modificationHistory.add(
        ModificationAttempt(
            timestamp = System.currentTimeMillis(),
            filePath = filePath,
            previousContent = oldContent,
            newContent = newContent,
            attemptNumber = currentAttemptCount,
            success = success,
        )
    )
  }

  override fun undoLastModification(): Boolean {
    val last = modificationHistory.lastOrNull { it.success } ?: return false
    val previous = last.previousContent ?: return false
    val result = writeFile(last.filePath, previous)
    return result is FileWriteResult.Success
  }

  override fun getModificationHistory(): List<ModificationAttempt> = modificationHistory.toList()

  override fun resetAttemptCount() {
    currentAttemptCount = 0
  }

  override fun incrementAttemptCount() {
    currentAttemptCount++
  }

  override fun getCurrentAttemptCount(): Int = currentAttemptCount

  override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

  override fun writeFile(filePath: String, content: String): FileWriteResult {
    val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
    return writer.writeFile(filePath, content, createBackup = true)
  }

  private fun isUserRequestingCorrection(message: String): Boolean {
    val correctionKeywords =
        listOf(
            "wrong",
            "not what",
            "mistake",
            "error",
            "incorrect",
            "that's not",
            "not right",
            "fix",
            "undo",
            "revert",
            "different",
            "try again",
            "not working",
        )
    return correctionKeywords.any { message.lowercase().contains(it) }
  }

  private fun readRelevantFiles(): Map<String, String> {
    val filesContent = mutableMapOf<String, String>()
    val tree = projectTreeResult?.tree ?: return filesContent

    tree.lines().filter { it.isNotBlank() }.forEach { line ->
      val trimmedPath = line.trim()
      val file = File(trimmedPath)
      if (
          file.isFile &&
              (trimmedPath.endsWith(".kt") ||
                  trimmedPath.endsWith(".java") ||
                  trimmedPath.endsWith(".xml") ||
                  trimmedPath.endsWith(".gradle") ||
                  trimmedPath.endsWith(".gradle.kts")) &&
              !trimmedPath.contains("/build/") &&
              !trimmedPath.contains("/.gradle/")
      ) {
        runCatching { file.readText() }
            .onSuccess { filesContent[trimmedPath] = it }
            .onFailure { log.debug("Unable to read {}", trimmedPath) }
      }
    }
    return filesContent
  }

  private data class ConversationMessage(val role: String, val content: String)

  companion object {

    /**
     * Registers (or re-registers) every configured custom provider.
     *
     * Call this whenever a provider is added, edited or removed so that
     * [com.tom.rv2ide.artificial.agents.AIAgentRegistry] and the provider switch dialog stay in
     * sync.
     */
    fun registerAgent(context: Context) {
      CustomProviders.all().forEach { provider ->
        AIAgentRegistry.register(
            provider.providerId,
            object : AIAgentRegistry.AgentFactory {
              override fun create(context: Context): AIAgent = CustomAgent(provider.id)

              override fun hasValidApiKey(): Boolean {
                val current = CustomProviders.byId(provider.id) ?: return false
                return current.baseUrl.isNotBlank()
              }

              override fun getApiKey(): String? = CustomProviders.byId(provider.id)?.apiKey
            },
        )
      }
    }

    /** Removes the registration of [providerId], if any. */
    fun unregister(providerId: String) {
      if (providerId.startsWith(CustomProvider.PROVIDER_PREFIX)) {
        AIAgentRegistry.unregister(providerId)
      }
    }
  }
}
