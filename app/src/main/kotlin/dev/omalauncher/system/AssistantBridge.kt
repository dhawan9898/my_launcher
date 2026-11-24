package dev.omalauncher.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Direct assistant integration bridge for Google Gemini and Samsung Bixby:
 * - Triggers instant voice or text prompts.
 * - Supports Bixby routines and Samsung S-Pen shortcuts.
 * - Allows seamless multi-assistant invocation from the liquid glass bar.
 */
object AssistantBridge {
    private const val TAG = "AssistantBridge"

    // Package identifiers
    private const val PKG_GEMINI = "com.google.android.apps.bard"
    private const val PKG_ASSISTANT = "com.google.android.apps.googleassistant"
    private const val PKG_GOOGLE = "com.google.android.googlequicksearchbox"
    private const val PKG_BIXBY = "com.samsung.android.bixby.agent"

    /**
     * Summons Google Gemini (or Google Assistant) with an optional query or voice interaction.
     */
    fun openGemini(context: Context, query: String? = null): Boolean {
        return try {
            if (!query.isNullOrBlank()) {
                // Query dispatch to Gemini / Assistant
                val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", query)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(searchIntent)
                true
            } else {
                // Voice command / Assistant summon
                val voiceIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(voiceIntent)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Gemini via voice command, trying package launch", e)
            openPackage(context, PKG_GEMINI) || openPackage(context, PKG_ASSISTANT) || openPackage(context, PKG_GOOGLE)
        }
    }

    /**
     * Summons Samsung Bixby Voice or Bixby Routines on Samsung Galaxy devices.
     */
    fun openBixby(context: Context): Boolean {
        return try {
            // Attempt standard Bixby Voice intent
            val bixbyIntent = Intent("samsung.intent.action.BIXBY_VOICE").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(bixbyIntent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Standard Bixby intent unavailable, trying direct Bixby component", e)
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(PKG_BIXBY)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    true
                } else {
                    false
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Cannot launch Bixby", ex)
                false
            }
        }
    }

    private fun openPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
