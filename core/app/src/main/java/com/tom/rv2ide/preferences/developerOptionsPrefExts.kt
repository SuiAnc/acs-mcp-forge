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

package com.tom.rv2ide.preferences

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.tom.rv2ide.R
import com.tom.rv2ide.mcp.McpServerManager
import com.tom.rv2ide.services.keepalive.KeepAlivePreferences
import com.tom.rv2ide.services.keepalive.KeepAliveService
import com.tom.rv2ide.utils.DialogUtils
import com.tom.rv2ide.preferences.internal.DevOpsPreferences
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.resources.R.string
import kotlinx.parcelize.Parcelize

@Parcelize
internal class DeveloperOptionsScreen(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS,
    override val title: Int = R.string.title_developer_options,
    override val summary: Int? = R.string.idepref_devOptions_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(DebuggingPreferences())
    addPreference(KeepAlivePreferencesGroup())
    addPreference(McpServerScreen())
  }
}

@Parcelize
internal class DebuggingPreferences(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_DEBUGGING,
    override val title: Int = R.string.idepref_group_debugging,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(DumpLogsPreference())
    addPreference(EnableLogSenderPreference())
    addPreference(IdeLogsEnabled())
  }
}

@Parcelize
internal class DumpLogsPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_DEBUGGING_DUMPLOGS,
    override val title: Int = R.string.idepref_devOptions_dumpLogs_title,
    override val summary: Int? = R.string.idepref_devOptions_dumpLogs_summary,
) :
    SwitchPreference(
        setValue = DevOpsPreferences::dumpLogs::set,
        getValue = DevOpsPreferences::dumpLogs::get,
    )

@Parcelize
internal class EnableLogSenderPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_DEBUGGING_ENABLE_LOGSENDER,
    override val title: Int = R.string.idepref_devOptions_enableLogsender_title,
    override val summary: Int? = R.string.idepref_devOptions_enableLogsender_summary,
) :
    SwitchPreference(
        setValue = DevOpsPreferences::logsenderEnabled::set,
        getValue = DevOpsPreferences::logsenderEnabled::get,
    )

@Parcelize
class IdeLogsEnabled(
    override val key: String = GeneralPreferences.IDE_LOGS_ENABLED,
    override val title: Int = string.title_enable_ide_logs,
    override val summary: Int? = string.msg_enable_ide_logs,
) : SwitchPreference() {

  override fun onCreatePreference(context: Context): Preference {
    val pref = super.onCreatePreference(context)
    if (pref is androidx.preference.SwitchPreference) {
      pref.isChecked = GeneralPreferences.ideLogsEnabled
    }
    return pref
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    GeneralPreferences.ideLogsEnabled = newValue as? Boolean ?: GeneralPreferences.ideLogsEnabled
    return true
  }
}

// ---------------------------------------------------------------------------------------------
// MCP server
// ---------------------------------------------------------------------------------------------

/**
 * Settings for the MCP (Model Context Protocol) server.
 *
 * The server listens on `127.0.0.1:6688` by default and exposes tools to list, read, create, edit,
 * rename, move and delete files, create and open projects and run Gradle builds.
 */
@Parcelize
internal class McpServerScreen(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP,
    override val title: Int = string.idepref_devOptions_mcp_title,
    override val summary: Int? = string.idepref_devOptions_mcp_summary,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceScreen() {

  init {
    addPreference(McpServerEnabledPreference())
    addPreference(McpServerHostPreference())
    addPreference(McpServerPortPreference())
    addPreference(McpServerTokenPreference())
    addPreference(McpTokenVisiblePreference())
    addPreference(McpGenerateTokenPreference())
    addPreference(McpCopyTokenPreference())
    addPreference(McpAllowRemotePreference())
    addPreference(McpAllowWritePreference())
    addPreference(McpAllowBuildPreference())
    addPreference(McpAllowNetworkPreference())
    addPreference(McpServerStatusPreference())
    addPreference(McpAuditLogPreference())
  }
}

@Parcelize
internal class McpServerEnabledPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_ENABLED,
    override val title: Int = string.idepref_devOptions_mcp_enabled_title,
    override val summary: Int? = string.idepref_devOptions_mcp_enabled_summary,
) :
    SwitchPreference(
        setValue = { enabled ->
          DevOpsPreferences.mcpServerEnabled = enabled
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpServerEnabled },
    )

@Parcelize
internal class McpServerHostPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_HOST,
    override val title: Int = string.idepref_devOptions_mcp_host_title,
    override val summary: Int? = string.idepref_devOptions_mcp_host_summary,
) : EditTextPreference() {

  override fun onConfigureTextInput(input: TextInputLayout) {
    input.setHint(DevOpsPreferences.DEFAULT_MCP_HOST)
    input.editText?.setText(DevOpsPreferences.mcpHost)
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    val host = (newValue as? String)?.trim().orEmpty()
    DevOpsPreferences.mcpHost =
        if (host.isEmpty()) DevOpsPreferences.DEFAULT_MCP_HOST else host
    McpServerManager.applyPreferences()
    return true
  }
}

@Parcelize
internal class McpServerPortPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_PORT,
    override val title: Int = string.idepref_devOptions_mcp_port_title,
    override val summary: Int? = string.idepref_devOptions_mcp_port_summary,
    private val hintRes: Int = string.idepref_devOptions_mcp_port_hint,
) :
    NumberInputEditTextPreference(
        hint = hintRes,
        setValue = { port ->
          DevOpsPreferences.mcpPort =
              if (port in 1024..65535) port else DevOpsPreferences.DEFAULT_MCP_PORT
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpPort },
    )

@Parcelize
internal class McpServerTokenPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_TOKEN,
    override val title: Int = string.idepref_devOptions_mcp_token_title,
    override val summary: Int? = string.idepref_devOptions_mcp_token_summary,
) : EditTextPreference() {

  override fun onConfigureTextInput(input: TextInputLayout) {
    input.editText?.apply {
      inputType =
          InputType.TYPE_CLASS_TEXT or
              if (DevOpsPreferences.mcpTokenVisible) {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
              } else {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
              }
      setText(DevOpsPreferences.mcpToken)
    }
  }

  override fun onPreferenceChanged(preference: Preference, newValue: Any?): Boolean {
    DevOpsPreferences.mcpToken = (newValue as? String).orEmpty()
    McpServerManager.applyPreferences()
    return true
  }
}

@Parcelize
internal class McpGenerateTokenPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_GENERATE_TOKEN,
    override val title: Int = string.idepref_devOptions_mcp_generate_token_title,
    override val summary: Int? = string.idepref_devOptions_mcp_generate_token_summary,
) : SimplePreference() {

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val token = McpServerManager.generateToken()
    DevOpsPreferences.mcpToken = token
    McpServerManager.applyPreferences()

    copyTokenToClipboard(context, token)

    // Show the token so that it can be read or selected manually. The toast is only used on
    // Android versions which do not show the system clipboard overlay.
    MaterialAlertDialogBuilder(context)
        .setTitle(string.idepref_devOptions_mcp_token_dialog_title)
        .setMessage(token)
        .setPositiveButton(android.R.string.ok, null)
        .show()

    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
      Toast.makeText(context, string.msg_mcp_token_copied, Toast.LENGTH_SHORT).show()
    }
    return true
  }
}

/** Switches the access token field between masked and plain text. */
@Parcelize
internal class McpTokenVisiblePreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_TOKEN_VISIBLE,
    override val title: Int = string.idepref_devOptions_mcp_token_visible_title,
    override val summary: Int? = string.idepref_devOptions_mcp_token_visible_summary,
) :
    SwitchPreference(
        setValue = { visible -> DevOpsPreferences.mcpTokenVisible = visible },
        getValue = { DevOpsPreferences.mcpTokenVisible },
    )

/** Copies the already stored access token to the clipboard. */
@Parcelize
internal class McpCopyTokenPreference(
    override val key: String = "ide.prefs.developerOptions.mcp.copyToken",
    override val title: Int = string.idepref_devOptions_mcp_token_copy_title,
    override val summary: Int? = string.idepref_devOptions_mcp_token_copy_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.idepref_devOptions_mcp_token_copy_summary)
    preference.setOnPreferenceClickListener {
      val token = DevOpsPreferences.mcpToken
      if (token.isBlank()) {
        Toast.makeText(context, string.msg_mcp_token_empty, Toast.LENGTH_SHORT).show()
      } else {
        copyTokenToClipboard(context, token)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
          Toast.makeText(context, string.msg_mcp_token_copied, Toast.LENGTH_SHORT).show()
        }
      }
      true
    }
    return preference
  }
}

@Parcelize
internal class McpAllowRemotePreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_ALLOW_REMOTE,
    override val title: Int = string.idepref_devOptions_mcp_allow_remote_title,
    override val summary: Int? = string.idepref_devOptions_mcp_allow_remote_summary,
) :
    SwitchPreference(
        setValue = { allow ->
          DevOpsPreferences.mcpAllowRemote = allow
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpAllowRemote },
    )

@Parcelize
internal class McpAllowWritePreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_ALLOW_WRITE,
    override val title: Int = string.idepref_devOptions_mcp_allow_write_title,
    override val summary: Int? = string.idepref_devOptions_mcp_allow_write_summary,
) :
    SwitchPreference(
        setValue = { allow ->
          DevOpsPreferences.mcpAllowWrite = allow
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpAllowWrite },
    )

@Parcelize
internal class McpAllowBuildPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_ALLOW_BUILD,
    override val title: Int = string.idepref_devOptions_mcp_allow_build_title,
    override val summary: Int? = string.idepref_devOptions_mcp_allow_build_summary,
) :
    SwitchPreference(
        setValue = { allow ->
          DevOpsPreferences.mcpAllowBuild = allow
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpAllowBuild },
    )

/**
 * Shows the current state of the MCP server. Tapping the item refreshes the summary.
 */
@Parcelize
internal class McpAllowNetworkPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_ALLOW_NETWORK,
    override val title: Int = string.idepref_devOptions_mcp_allow_network_title,
    override val summary: Int? = string.idepref_devOptions_mcp_allow_network_summary,
) :
    SwitchPreference(
        setValue = { allow ->
          DevOpsPreferences.mcpAllowNetwork = allow
          McpServerManager.applyPreferences()
        },
        getValue = { DevOpsPreferences.mcpAllowNetwork },
    )

@Parcelize
internal class McpServerStatusPreference(
    override val key: String = DevOpsPreferences.KEY_DEVOPTS_MCP_STATUS,
    override val title: Int = string.idepref_devOptions_mcp_status_title,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = McpServerManager.statusSummary()
    preference.setOnPreferenceClickListener { clicked ->
      val summary = McpServerManager.statusSummary()
      clicked.summary = summary
      Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
      true
    }
    return preference
  }
}

/**
 * Shows the recent MCP tool invocations recorded in the audit log.
 */
@Parcelize
internal class McpAuditLogPreference(
    override val key: String = "${DevOpsPreferences.KEY_DEVOPTS_MCP}.auditLog",
    override val title: Int = string.idepref_devOptions_mcp_audit_title,
    override val summary: Int? = string.idepref_devOptions_mcp_audit_summary,
) : SimplePreference() {

  override fun onPreferenceClick(preference: Preference): Boolean {
    val context = preference.context
    val entries = McpServerManager.recentAuditLog(100)
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    val container =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(16), dp(8), dp(16), dp(8))
        }

    if (entries.isEmpty()) {
      container.addView(
          TextView(context).apply {
            text = context.getString(string.msg_mcp_audit_empty)
            setPadding(0, dp(8), 0, dp(8))
          }
      )
    } else {
      entries.asReversed().forEachIndexed { index, entry ->
        val row =
            LinearLayout(context).apply {
              orientation = LinearLayout.VERTICAL
              setPadding(0, dp(8), 0, dp(8))
            }
        row.addView(
            TextView(context).apply {
              text = "${entry.timeText}  ${entry.tool}"
              textSize = 13f
              setTypeface(typeface, Typeface.BOLD)
            }
        )
        row.addView(
            TextView(context).apply {
              text =
                  context.getString(
                      string.msg_mcp_audit_entry_detail,
                      operationLabel(context, entry.op),
                      entry.durationMs,
                      if (entry.isError) {
                        context.getString(string.msg_mcp_audit_failed)
                      } else {
                        context.getString(string.msg_mcp_audit_ok)
                      },
                  )
              textSize = 11f
              setTextColor(0xFF888888.toInt())
            }
        )
        container.addView(row)

        if (index < entries.lastIndex) {
          container.addView(
              View(context).apply {
                setBackgroundColor(0x33888888)
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    )
              }
          )
        }
      }
    }

    MaterialAlertDialogBuilder(context)
        .setTitle(string.idepref_devOptions_mcp_audit_title)
        .setView(ScrollView(context).apply { addView(container) })
        .setPositiveButton(android.R.string.ok, null)
        .setNeutralButton(string.msg_mcp_audit_copy) { _, _ ->
          copyAuditEntriesToClipboard(context, entries)
        }
        .setNegativeButton(string.msg_mcp_audit_clear) { _, _ ->
          McpServerManager.clearAuditLog()
          Toast.makeText(context, string.msg_mcp_audit_cleared, Toast.LENGTH_SHORT).show()
        }
        .show()
    return true
  }

  private fun operationLabel(context: Context, op: String): String {
    return when (op) {
      "read" -> context.getString(string.msg_mcp_audit_type_read)
      "write" -> context.getString(string.msg_mcp_audit_type_write)
      "build" -> context.getString(string.msg_mcp_audit_type_build)
      "network" -> context.getString(string.msg_mcp_audit_type_network)
      else -> context.getString(string.msg_mcp_audit_type_unknown)
    }
  }

  private fun copyAuditEntriesToClipboard(
      context: Context,
      entries: List<com.tom.rv2ide.mcp.security.AuditLog.AuditEntry>,
  ) {
    if (entries.isEmpty()) {
      Toast.makeText(context, string.msg_mcp_audit_empty, Toast.LENGTH_SHORT).show()
      return
    }
    val text =
        buildString {
          append(context.getString(string.msg_mcp_audit_copy_header)).append('\n')
          entries.asReversed().forEach { entry ->
            append(entry.timeText).append('\t')
            append(operationLabel(context, entry.op)).append('\t')
            append(entry.tool).append('\t')
            append(entry.durationMs).append("ms").append('\t')
            append(if (entry.isError) "!" else "ok").append('\n')
          }
        }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(string.idepref_devOptions_mcp_audit_title), text)
    )
    Toast.makeText(context, string.msg_mcp_audit_copied, Toast.LENGTH_SHORT).show()
  }
}


// ---------------------------------------------------------------------------------------------
// Keep alive
// ---------------------------------------------------------------------------------------------

/** Keep alive switches: foreground service notification and the floating overlay button. */
@Parcelize
internal class KeepAlivePreferencesGroup(
    override val key: String = "ide.prefs.developerOptions.keepAlive.group",
    override val title: Int = string.idepref_keepAlive_group,
    override val children: List<IPreference> = mutableListOf(),
) : IPreferenceGroup() {

  init {
    addPreference(KeepAliveEnabledPreference())
    addPreference(KeepAliveNotificationPreference())
    addPreference(KeepAliveFloatingPreference())
    addPreference(KeepAliveEdgeDelayPreference())
    addPreference(KeepAliveAlphaPreference())
    addPreference(KeepAliveOverlayPermissionPreference())
  }
}

@Parcelize
internal class KeepAliveEnabledPreference(
    override val key: String = KeepAlivePreferences.KEY_ENABLED,
    override val title: Int = string.idepref_keepAlive_enabled_title,
    override val summary: Int? = string.idepref_keepAlive_enabled_summary,
) :
    SwitchPreference(
        setValue = { enabled ->
          KeepAlivePreferences.enabled = enabled
          KeepAliveService.applyPreferences(prefContext())
        },
        getValue = { KeepAlivePreferences.enabled },
    )

@Parcelize
internal class KeepAliveNotificationPreference(
    override val key: String = KeepAlivePreferences.KEY_NOTIFICATION,
    override val title: Int = string.idepref_keepAlive_notification_title,
    override val summary: Int? = string.idepref_keepAlive_notification_summary,
) :
    SwitchPreference(
        setValue = { enabled ->
          KeepAlivePreferences.notificationEnabled = enabled
          KeepAliveService.applyPreferences(prefContext())
        },
        getValue = { KeepAlivePreferences.notificationEnabled },
    )

@Parcelize
internal class KeepAliveFloatingPreference(
    override val key: String = KeepAlivePreferences.KEY_FLOATING,
    override val title: Int = string.idepref_keepAlive_floating_title,
    override val summary: Int? = string.idepref_keepAlive_floating_summary,
) :
    SwitchPreference(
        setValue = { enabled ->
          KeepAlivePreferences.floatingEnabled = enabled
          KeepAliveService.applyPreferences(prefContext())
        },
        getValue = { KeepAlivePreferences.floatingEnabled },
    )

@Parcelize
internal class KeepAliveEdgeDelayPreference(
    override val key: String = KeepAlivePreferences.KEY_EDGE_DELAY,
    override val title: Int = string.idepref_keepAlive_edge_delay_title,
    override val summary: Int? = string.idepref_keepAlive_edge_delay_summary,
) :
    NumberInputEditTextPreference(
        hint = string.idepref_keepAlive_edge_delay_hint,
        setValue = { value -> KeepAlivePreferences.edgeDelayMs = value },
        getValue = { KeepAlivePreferences.edgeDelayMs },
    )

@Parcelize
internal class KeepAliveAlphaPreference(
    override val key: String = KeepAlivePreferences.KEY_ALPHA,
    override val title: Int = string.idepref_keepAlive_alpha_title,
    override val summary: Int? = string.idepref_keepAlive_alpha_summary,
) :
    NumberInputEditTextPreference(
        hint = string.idepref_keepAlive_alpha_hint,
        setValue = { value ->
          KeepAlivePreferences.alphaPercent = value
          KeepAliveService.applyPreferences(prefContext())
        },
        getValue = { KeepAlivePreferences.alphaPercent },
    )

@Parcelize
internal class KeepAliveOverlayPermissionPreference(
    override val key: String = "ide.prefs.developerOptions.keepAlive.overlayPermission",
    override val title: Int = string.idepref_keepAlive_permission_title,
    override val summary: Int? = string.idepref_keepAlive_permission_summary,
) : IPreference() {

  override fun onCreateView(context: Context): Preference {
    val preference = Preference(context)
    preference.key = key
    preference.title = context.getString(title)
    preference.summary = context.getString(string.idepref_keepAlive_permission_summary)
    preference.setOnPreferenceClickListener {
      val canDraw =
          android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
              Settings.canDrawOverlays(context)
      if (canDraw) {
        Toast.makeText(context, string.idepref_keepAlive_permission_granted, Toast.LENGTH_SHORT)
            .show()
      } else {
        runCatching {
          context.startActivity(
              android.content.Intent(
                      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      Uri.parse("package:" + context.packageName),
                  )
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
      }
      true
    }
    return preference
  }
}

/** Small helper so the preference callbacks can start the service without an Activity reference. */
private fun prefContext(): android.content.Context {
  return com.tom.rv2ide.app.BaseApplication.getBaseInstance()
}

/** Copies [token] to the system clipboard. */
private fun copyTokenToClipboard(context: Context, token: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      ?: return
  clipboard.setPrimaryClip(
      ClipData.newPlainText(context.getString(string.msg_mcp_token_clip_label), token)
  )
}
