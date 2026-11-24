package com.tom.rv2ide.fragments.sidebar

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.custom.CustomProviders
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import com.tom.rv2ide.managers.CodeCompletionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.tom.rv2ide.artificial.dialogs.LocalLLMConfigDialog

class AIPreferencesFragment(
    private val aiAgent: AIAgentManager,
    private val agents: Agents,
    private val codeCompletionManager: CodeCompletionManager?
) : Fragment() {

    private lateinit var providerDropdown: AutoCompleteTextView
    private lateinit var modelDropdown: AutoCompleteTextView
    private lateinit var autoSwitchToggle: MaterialSwitch
    private lateinit var codeCompletionToggle: MaterialSwitch
    private lateinit var currentProviderText: MaterialTextView
    private lateinit var currentModelText: MaterialTextView
    private lateinit var manageCustomProvidersButton: MaterialButton
    
    private val providerSwitchDialog by lazy { ProviderSwitchDialog(requireContext()) }
    
    private var completionStateMonitorJob: Job? = null
    private var isCompletionEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupProviderDropdown()
        setupModelDropdown()
        setupToggles()
        updateCurrentStatus()
        startCompletionStateMonitoring()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentStatus()
        updateProviderDropdownSelection()
        updateModelDropdown()
        syncCodeCompletionToggle()
    }
    
    override fun onPause() {
        super.onPause()
        stopCompletionStateMonitoring()
    }

    private fun initializeViews(view: View) {
        providerDropdown = view.findViewById(R.id.providerDropdown)
        modelDropdown = view.findViewById(R.id.modelDropdown)
        autoSwitchToggle = view.findViewById(R.id.autoSwitchToggle)
        codeCompletionToggle = view.findViewById(R.id.codeCompletionToggle)
        currentProviderText = view.findViewById(R.id.currentProviderText)
        currentModelText = view.findViewById(R.id.currentModelText)
        manageCustomProvidersButton = view.findViewById(R.id.manageCustomProvidersButton)

        manageCustomProvidersButton.setOnClickListener {
            // The list/editor screens live in the preferences tree; reuse them from here instead of
            // duplicating the UI in the sidebar.
            (parentFragment as? ArtificialFragment)?.openCustomProviders()
        }
    }

    private data class ProviderEntry(val id: String, val label: String)

    private val builtInProviders =
        listOf(
            ProviderEntry("gemini", "Google Gemini"),
            ProviderEntry("openai", "OpenAI"),
            ProviderEntry("claude", "Anthropic Claude"),
            ProviderEntry("deepseek", "DeepSeek"),
            ProviderEntry("grok", "xAI Grok"),
            ProviderEntry("localllm", "Local LLM"),
        )

    /** Built-in providers plus the providers defined by the user. */
    private fun providerEntries(): List<ProviderEntry> {
        val custom =
            CustomProviders.all().map { provider ->
                ProviderEntry(
                    id = CustomProviders.providerIdOf(provider.id),
                    label = provider.name.ifBlank { "Custom" },
                )
            }
        return builtInProviders + custom
    }

    private fun setupProviderDropdown() {
        val entries = providerEntries()

        val adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                entries.map { it.label },
            )
        providerDropdown.setAdapter(adapter)

        updateProviderDropdownSelection()

        providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener

            if (entry.id == "localllm") {
                showLocalLLMConfigDialog(entry.label)
            } else {
                handleProviderChange(entry.id, entry.label)
            }
        }
    }
    
    private fun showLocalLLMConfigDialog(providerName: String) {
        val dialog = LocalLLMConfigDialog { baseUrl, modelName ->
            handleProviderChange("localllm", providerName)
        }
        dialog.show(parentFragmentManager, "LocalLLMConfigDialog")
    }
    
    private fun updateProviderDropdownSelection() {
        val currentProviderId = agents.getProvider()
        val entry = providerEntries().firstOrNull { it.id == currentProviderId }
        providerDropdown.setText(entry?.label ?: currentProviderId, false)
    }

    private fun updateCurrentStatus() {
        val currentProvider = agents.getProvider()
        val currentModel = agents.getAgent()

        val entry = providerEntries().firstOrNull { it.id == currentProvider }
        val providerDisplayName =
            entry?.label
                ?: CustomProviders.idOfProviderId(currentProvider)?.let { id ->
                      CustomProviders.byId(id)?.name?.ifBlank { "Custom" }
                  }
                ?: currentProvider.uppercase()

        currentProviderText.text = providerDisplayName
        currentModelText.text = currentModel.ifBlank { getString(R.string.ai_sidebar_not_set) }
    }

    private fun setupModelDropdown() {
        updateModelDropdown()

        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            val models = modelsForProvider(agents.getProvider())

            if (position < models.size) {
                handleModelChange(models[position])
            }
        }
    }

    /**
     * Models which belong to [providerId].
     *
     * User defined providers keep their own model list, so they must not fall back to the models of
     * a built-in provider.
     */
    private fun modelsForProvider(providerId: String): Array<String> {
        CustomProviders.idOfProviderId(providerId)?.let { id ->
            val provider = CustomProviders.byId(id)
            val models =
                provider?.models.orEmpty().ifEmpty {
                    listOfNotNull(provider?.model?.takeIf { model -> model.isNotBlank() })
                }
            return models.toTypedArray()
        }

        return agents.getModelsForProvider(providerId)
    }

    private fun updateModelDropdown() {
        val models = modelsForProvider(agents.getProvider())

        val adapter =
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                models.toList(),
            )
        modelDropdown.setAdapter(adapter)

        val currentModel = agents.getAgent()
        when {
            currentModel.isNotBlank() && currentModel in models ->
                modelDropdown.setText(currentModel, false)
            models.isNotEmpty() -> modelDropdown.setText(models[0], false)
            else -> modelDropdown.setText("", false)
        }
    }

    private fun setupToggles() {
        autoSwitchToggle.isChecked = providerSwitchDialog.isAutoSwitchEnabled()
        autoSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
            providerSwitchDialog.setAutoSwitch(isChecked)
            val message =
                getString(
                    if (isChecked) R.string.ai_sidebar_msg_auto_switch_on
                    else R.string.ai_sidebar_msg_auto_switch_off
                )
            showSnackbar(message)
        }
        
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
        
        codeCompletionToggle.setOnCheckedChangeListener { _, isChecked ->
            android.util.Log.d("AIPreferences", "Toggle changed to: $isChecked")
            
            isCompletionEnabled = isChecked
            
            requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("code_completion_enabled", isChecked)
                .apply()
            
            lifecycleScope.launch {
                applyCompletionStateChange(isChecked)
            }
            
            val message =
                getString(
                    if (isChecked) R.string.ai_sidebar_msg_completion_on
                    else R.string.ai_sidebar_msg_completion_off
                )
            showSnackbar(message)
        }
    }
    
    private fun startCompletionStateMonitoring() {
        stopCompletionStateMonitoring()
        
        completionStateMonitorJob = lifecycleScope.launch {
            while (true) {
                delay(100)
                
                val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
                    .getBoolean("code_completion_enabled", true)
                
                if (savedState != isCompletionEnabled) {
                    android.util.Log.d("AIPreferences", "State mismatch detected: saved=$savedState, current=$isCompletionEnabled")
                    isCompletionEnabled = savedState
                    
                    if (codeCompletionToggle.isChecked != savedState) {
                        codeCompletionToggle.isChecked = savedState
                    }
                    
                    applyCompletionStateChange(savedState)
                }
            }
        }
    }
    
    private fun stopCompletionStateMonitoring() {
        completionStateMonitorJob?.cancel()
        completionStateMonitorJob = null
    }
    
    private suspend fun applyCompletionStateChange(enabled: Boolean) {
        android.util.Log.d("AIPreferences", "Applying completion state change: $enabled")
        
        if (enabled) {
            codeCompletionManager?.reattachToCurrentEditor()
            android.util.Log.d("AIPreferences", "Re-enabled code completion")
        } else {
            codeCompletionManager?.cleanup()
            android.util.Log.d("AIPreferences", "Disabled code completion")
        }
    }

    private fun syncCodeCompletionToggle() {
        val savedState = requireContext().getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            .getBoolean("code_completion_enabled", true)
        
        android.util.Log.d("AIPreferences", "Syncing toggle: saved=$savedState")
        
        isCompletionEnabled = savedState
        codeCompletionToggle.isChecked = savedState
    }

    private fun handleProviderChange(providerId: String, providerName: String) {
        android.util.Log.d("AIPreferences", "Switching to provider: $providerId")
        
        val availableModels = modelsForProvider(providerId)
        android.util.Log.d("AIPreferences", "Available models for $providerId: ${availableModels.joinToString()}")

        if (availableModels.isEmpty() && CustomProviders.idOfProviderId(providerId) != null) {
            showSnackbar(getString(R.string.ai_sidebar_msg_custom_models_empty))
        }
        
        if (availableModels.isNotEmpty()) {
            val defaultModel = availableModels[0]
            agents.setAgent(defaultModel)
            android.util.Log.d("AIPreferences", "Set default model: $defaultModel")
        }
        
        agents.setProvider(providerId)
        
        updateModelDropdown()
        
        if (aiAgent.setProvider(providerId)) {
            aiAgent.reinitializeWithSelectedModel()
            updateCurrentStatus()
            
            lifecycleScope.launch {
                if (isCompletionEnabled) {
                    delay(500)
                    codeCompletionManager?.reattachToCurrentEditor()
                    android.util.Log.d("AIPreferences", "Reattached completion after provider change")
                }
            }
            
            showSnackbar(getString(R.string.ai_sidebar_msg_provider_switched, providerName))
        } else {
            showSnackbar(getString(R.string.ai_sidebar_msg_missing_key, providerName))
        }
    }

    private fun handleModelChange(modelName: String) {
        android.util.Log.d("AIPreferences", "Switching to model: $modelName")
        agents.setAgent(modelName)
        aiAgent.reinitializeWithSelectedModel()
        updateCurrentStatus()
        
        lifecycleScope.launch {
            if (isCompletionEnabled) {
                delay(500)
                codeCompletionManager?.reattachToCurrentEditor()
                android.util.Log.d("AIPreferences", "Reattached completion after model change")
            }
        }
        
        showSnackbar(getString(R.string.ai_sidebar_msg_model_switched, modelName))
    }

    private fun showSnackbar(message: String) {
        view?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroyView() {
        stopCompletionStateMonitoring()
        super.onDestroyView()
    }
}