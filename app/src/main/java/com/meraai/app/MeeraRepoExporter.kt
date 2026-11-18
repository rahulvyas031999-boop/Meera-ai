package com.meraai.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.NumberFormat
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object MeeraTokenVault {
    private const val PREF_NAME = "meera_secure_github_vault"
    private const val KEY_ENC_TOKEN = "enc_github_token"
    private const val KEY_IV = "enc_github_iv"
    private const val KEYSTORE_ALIAS = "meera_github_pat_aes_key"

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun hasToken(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !sp.getString(KEY_ENC_TOKEN, null).isNullOrBlank()
    }

    fun saveToken(ctx: Context, rawToken: String) {
        val clean = rawToken.trim()
        if (clean.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENC_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    fun getToken(ctx: Context): String {
        return try {
            val sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val encB64 = sp.getString(KEY_ENC_TOKEN, null) ?: return ""
            val ivB64 = sp.getString(KEY_IV, null) ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP)), Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            ""
        }
    }

    fun clearToken(ctx: Context) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

data class RepoExportUiState(
    val running: Boolean = false,
    val statusText: String = "",
    val completedFileName: String? = null,
    val completedSizeKb: Int = 0,
    val completedFileCount: Int = 0,
    val shareUri: Uri? = null,
    val errorMsg: String? = null
)

object MeeraRepoExporter {
    private const val OWNER = "rahulvyas031999-boop"
    private const val REPO = "Meera-ai"
    private const val BRANCH = "v2-test"
    private const val OUTPUT_FILENAME = "meera_current_complete_code.txt"

    private val CODE_EXTS = listOf(".kt", ".java", ".xml", ".kts", ".properties", ".yml", ".yaml", ".md", ".json")
    val state = MutableStateFlow(RepoExportUiState())

    fun dismissCompletedDialog() {
        state.value = state.value.copy(completedFileName = null, shareUri = null, errorMsg = null)
    }

    fun exportFullCode(
        ctx: Context,
        explicitToken: String? = null,
        onNeedPrivateToken: () -> Unit
    ) {
        if (state.value.running) {
            Toast.makeText(ctx, "Code report pehle se ban rahi hai...", Toast.LENGTH_SHORT).show()
            return
        }

        val app = ctx.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        state.value = RepoExportUiState(running = true, statusText = "GitHub se code check ho raha hai...")

        Thread {
            try {
                var usedToken = explicitToken?.trim().orEmpty()
                var zipBytes = downloadZipball(usedToken)

                if (zipBytes == null && usedToken.isBlank()) {
                    val savedVaultToken = MeeraTokenVault.getToken(app)
                    if (savedVaultToken.isNotBlank()) {
                        usedToken = savedVaultToken
                        zipBytes = downloadZipball(usedToken)
                    }
                }

                if (zipBytes == null) {
                    state.value = RepoExportUiState(running = false)
                    mainHandler.post { onNeedPrivateToken() }
                    return@Thread
                }

                val recentCommits = fetchRecentCommits(usedToken)
                val (reportContent, codeFileCount) = buildCompleteReport(zipBytes, recentCommits)
                val reportBytes = reportContent.toByteArray(Charsets.UTF_8)
                val sizeKb = (reportBytes.size / 1024).coerceAtLeast(1)
                val shareUri = saveReportAndGetShareUri(app, reportBytes)

                state.value = RepoExportUiState(
                    running = false,
                    completedFileName = OUTPUT_FILENAME,
                    completedSizeKb = sizeKb,
                    completedFileCount = codeFileCount,
                    shareUri = shareUri
                )
                MeeraDebugLog.log("REPO_EXPORT", "Exported $codeFileCount files ($sizeKb KB) to Downloads/$OUTPUT_FILENAME")
            } catch (e: Exception) {
                state.value = RepoExportUiState(
                    running = false,
                    errorMsg = e.message ?: "Code report banane mein dikkat aayi."
                )
            }
        }.start()
    }

    private fun openGitHubConn(urlStr: String, token: String): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "MeeraAI-RepoExporter")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }

    private fun downloadZipball(token: String): ByteArray? {
        val url = "https://api.github.com/repos/$OWNER/$REPO/zipball/$BRANCH"
        val conn = openGitHubConn(url, token)
        val code = conn.responseCode
        if (code == 401 || code == 403 || code == 404) {
            conn.disconnect()
            return null
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("GitHub HTTP Error $code")
        }
        return conn.inputStream.use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        }
    }

    private fun fetchRecentCommits(token: String): String {
        return try {
            val url = "https://api.github.com/repos/$OWNER/$REPO/commits?sha=$BRANCH&per_page=15"
            val conn = openGitHubConn(url, token)
            if (conn.responseCode !in 200..299) return "(Commits history unavailable)\n"
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val arr = JSONArray(raw)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val sha7 = item.optString("sha", "").take(7)
                val msg = item.optJSONObject("commit")?.optString("message", "")
                    ?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                if (sha7.isNotBlank()) {
                    sb.append("$sha7 $msg\n")
                }
            }
            sb.toString().ifBlank { "(No commits found)\n" }
        } catch (_: Exception) {
            "(Commits history unavailable)\n"
        }
    }

    private fun buildCompleteReport(zipBytes: ByteArray, recentCommits: String): Pair<String, Int> {
        val fileMap = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BufferedInputStream(ByteArrayInputStream(zipBytes))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fullName = entry.name
                    val slashIdx = fullName.indexOf('/')
                    val relPath = if (slashIdx >= 0) fullName.substring(slashIdx + 1) else fullName
                    if (relPath.isNotBlank()) {
                        val bos = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        while (true) {
                            val r = zis.read(buf)
                            if (r < 0) break
                            bos.write(buf, 0, r)
                        }
                        fileMap[relPath] = bos.toByteArray()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sortedPaths = fileMap.keys.sorted()
        val numFmt = NumberFormat.getIntegerInstance(Locale.US)
        val out = StringBuilder(fileMap.values.sumOf { it.size } + 16384)

        out.append("========================================================================\n")
        out.append("MEERA-AI ($BRANCH) CURRENT COMPLETE SOURCE CODE DUMP\n")
        out.append("========================================================================\n\n")

        out.append("--- 1. RECENT 15 COMMITS ---\n")
        out.append(recentCommits).append("\n")

        out.append("--- 2. COMPLETE REPOSITORY FILE TREE ---\n")
        for (p in sortedPaths) {
            val sz = fileMap[p]?.size ?: 0
            out.append(" - ").append(p).append(" (").append(numFmt.format(sz)).append(" bytes)\n")
        }
        out.append("\n--- 3. FULL SOURCE CODE OF ALL CODE & CONFIG FILES ---\n")

        var codeCount = 0
        for (p in sortedPaths) {
            if (CODE_EXTS.any { p.endsWith(it, ignoreCase = true) }) {
                codeCount++
                out.append("\n===========================================================================\n")
                out.append("FILE: ").append(p).append("\n")
                out.append("===========================================================================\n")
                val text = String(fileMap[p] ?: ByteArray(0), Charsets.UTF_8)
                val lines = text.split("\n")
                for (i in lines.indices) {
                    val cleanLine = lines[i].removeSuffix("\r")
                    if (i == lines.lastIndex && cleanLine.isEmpty()) continue
                    out.append(String.format(Locale.US, "%4d | %s\n", i + 1, cleanLine))
                }
            }
        }

        return out.toString() to codeCount
    }

    private fun saveReportAndGetShareUri(app: Context, data: ByteArray): Uri? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, OUTPUT_FILENAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                }
            } else {
                @Suppress("DEPRECATION")
                val pubDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!pubDir.exists()) pubDir.mkdirs()
                File(pubDir, OUTPUT_FILENAME).writeBytes(data)
            }
        } catch (_: Exception) {}

        return try {
            val shareDir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir
            if (!shareDir.exists()) shareDir.mkdirs()
            val shareFile = File(shareDir, OUTPUT_FILENAME)
            shareFile.writeBytes(data)
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", shareFile)
        } catch (_: Exception) {
            null
        }
    }

    fun launchShareSheet(ctx: Context, uri: Uri) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Meera AI Complete Source Code Report ($BRANCH)")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Code Report Bhejein").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Share sheet nahi khul payi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun GitHubTokenVaultDialog(
    hasSavedToken: Boolean,
    isForPrivateDownload: Boolean,
    onSaveAndDownload: (token: String, saveEncrypted: Boolean) -> Unit,
    onSaveOnly: (token: String) -> Unit,
    onDeleteToken: () -> Unit,
    onDismiss: () -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }
    var showChars by remember { mutableStateOf(false) }
    var saveEncrypted by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isForPrivateDownload) "🔐 Private Repo Token Dalein"
                else "🔐 GitHub Token Vault (Encrypted)"
            )
        },
        text = {
            Column {
                Text(
                    if (isForPrivateDownload)
                        "Yeh repository private hai. Code download karne ke liye apna GitHub Token dalein. Public repo ke liye token ki zaroorat nahi hoti."
                    else if (hasSavedToken)
                        "Aapka GitHub Token Android Keystore se AES-256 encrypted roop mein surakshit hai. Naya token daal kar update kar sakte hain ya hata sakte hain."
                    else
                        "Agar aapki repository private hai to yahan GitHub Personal Access Token save kar sakte hain. Yeh hardware Keystore se encrypt rahega.",
                    fontSize = 13.sp,
                    color = Color(0xFF8A94B8)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    singleLine = true,
                    label = { Text("ghp_xxxxxxxxxxxx") },
                    visualTransformation = if (showChars) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showChars = !showChars }) {
                            Text(if (showChars) "Chhupayein" else "Dikhayein", fontSize = 12.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { saveEncrypted = !saveEncrypted }
                ) {
                    Checkbox(
                        checked = saveEncrypted,
                        onCheckedChange = { saveEncrypted = it }
                    )
                    Text(
                        "Token ko encrypted save karein (Android Keystore)",
                        fontSize = 12.5.sp,
                        color = Color.White
                    )
                }
                if (hasSavedToken) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onDeleteToken) {
                        Text("🗑️ Saved Encrypted Token Hatayein", color = Color(0xFFFF6B6B), fontSize = 12.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = tokenInput.isNotBlank(),
                onClick = {
                    val clean = tokenInput.trim()
                    if (isForPrivateDownload) {
                        onSaveAndDownload(clean, saveEncrypted)
                    } else {
                        onSaveOnly(clean)
                    }
                }
            ) {
                Text(
                    if (isForPrivateDownload) "Download Karein" else "Encrypted Save",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Radd")
            }
        }
    )
}
