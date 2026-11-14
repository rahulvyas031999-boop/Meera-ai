package com.meraai.app

import android.content.Context
import android.content.Intent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MeeraColabBridge {
    private const val PREF_NAME = "meera_colab_bridge"
    private const val KEY_URL = "colab_server_url"
    private const val KEY_SECRET = "colab_server_secret"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun getServerUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_URL, "")?.trim().orEmpty()

    fun setServerConfig(ctx: Context, url: String, secret: String = "meera123") {
        val cleanUrl = url.trim().trimEnd('/')
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, cleanUrl)
            .putString(KEY_SECRET, secret.trim().ifBlank { "meera123" })
            .apply()
    }

    private fun getSecret(ctx: Context): String =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SECRET, "meera123") ?: "meera123"

    /** Lightweight Local Execution via Termux Background Engine + Live Output Capture */
    fun runTermuxCommand(ctx: Context, command: String, argsCsv: String = ""): ToolExecutionResult {
        val fullCmd = if (argsCsv.isBlank()) command.trim() else "${command.trim()} $argsCsv"
        return MeeraTermuxEngine.execute(ctx, fullCmd, background = true, waitMs = 15000L, autoOpenUi = true)
    }

    /** Heavy Cloud Execution on Google Colab Backend Server */
    fun runColabTask(ctx: Context, taskType: String, instruction: String, payload: String = ""): ToolExecutionResult {
        val baseUrl = getServerUrl(ctx)
        if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            return ToolExecutionResult(
                true, "run_colab_task",
                "अभी Google Colab सर्वर का URL सेट नहीं है। Colab सेल चलाकर उसका पब्लिक लिंक और सीक्रेट मुझे बता दें या चैट में लिख दें!",
                "FAILED_NO_SERVER: Colab URL is not configured. Ask user to provide the public Colab tunnel URL and secret and save them with set_colab_url."
            )
        }

        return try {
            val jsonBody = JSONObject()
                .put("secret", getSecret(ctx))
                .put("task_type", taskType.ifBlank { "python_or_general" })
                .put("instruction", instruction)
                .put("payload", payload)

            val req = Request.Builder()
                .url("$baseUrl/execute")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ToolExecutionResult(
                        true, "run_colab_task",
                        "Colab सर्वर से संपर्क नहीं हो पाया (HTTP ${resp.code})। हो सकता है Colab सेशन बंद हो गया हो।",
                        "ERROR: Colab HTTP ${resp.code} -> ${raw.take(200)}"
                    )
                }
                val j = JSONObject(raw)
                val ok = j.optBoolean("ok", true)
                val summary = j.optString("summary", "Colab पर काम पूरा हो गया है।")
                val output = j.optString("output", "")
                if (output.isNotBlank()) {
                    MeeraAgentFactory.onDeliverToChat?.invoke("Google Colab Cloud", "☁️ [Colab Result]:\n$output")
                }
                ToolExecutionResult(
                    true, "run_colab_task",
                    summary,
                    if (ok) "VERIFIED_SUCCESS: Colab executed task -> $summary\nOutput: ${output.take(600)}"
                    else "FAILED: Colab error -> $summary"
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                true, "run_colab_task",
                "Colab सर्वर से कनेक्शन टूट गया है। कृपया नया Colab लिंक चेक करें।",
                "ERROR: Colab connection failed -> ${e.message}"
            )
        }
    }
}
