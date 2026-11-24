package com.meraai.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class SharedFileItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val kind: String,
    val localPath: String,
    val sizeBytes: Long,
    val timestamp: Long,
    val timeLabel: String,
    val extractedPreview: String = ""
)

object MeeraFileVault {
    private const val VAULT_DIR = "shared_vault"
    private const val INDEX_FILE = "index.json"
    private const val MAX_ITEMS = 30
    private const val MAX_COPY_BYTES = 25L * 1024L * 1024L

    val filesFlow = MutableStateFlow<List<SharedFileItem>>(emptyList())
    val activeFileFlow = MutableStateFlow<SharedFileItem?>(null)
    val chipVisibleFlow = MutableStateFlow(false)
    val vaultScreenOpenFlow = MutableStateFlow(false)

    @Volatile private var loaded = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun vaultDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.filesDir, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val idxFile = File(vaultDir(ctx), INDEX_FILE)
        if (!idxFile.exists()) return
        try {
            val arr = JSONArray(idxFile.readText(Charsets.UTF_8))
            val list = mutableListOf<SharedFileItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("localPath", "")
                if (path.isBlank() || !File(path).exists()) continue
                list.add(
                    SharedFileItem(
                        id = o.optString("id"),
                        name = o.optString("name", "file"),
                        mimeType = o.optString("mimeType", "*/*"),
                        kind = o.optString("kind", "OTHER"),
                        localPath = path,
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        timestamp = o.optLong("timestamp", 0L),
                        timeLabel = o.optString("timeLabel", ""),
                        extractedPreview = o.optString("extractedPreview", "")
                    )
                )
            }
            filesFlow.value = list
            if (activeFileFlow.value == null && list.isNotEmpty()) {
                activeFileFlow.value = list.first()
            }
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "load index error: ${e.message}")
        }
    }

    @Synchronized
    private fun persistIndex(ctx: Context, list: List<SharedFileItem>) {
        try {
            val arr = JSONArray()
            list.forEach { it ->
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("mimeType", it.mimeType)
                        .put("kind", it.kind)
                        .put("localPath", it.localPath)
                        .put("sizeBytes", it.sizeBytes)
                        .put("timestamp", it.timestamp)
                        .put("timeLabel", it.timeLabel)
                        .put("extractedPreview", it.extractedPreview)
                )
            }
            File(vaultDir(ctx), INDEX_FILE).writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "save index error: ${e.message}")
        }
    }

    fun dismissChip() { chipVisibleFlow.value = false }
    fun openVaultScreen() { vaultScreenOpenFlow.value = true }
    fun closeVaultScreen() { vaultScreenOpenFlow.value = false }

    private fun recordFileInLongTermMemory(ctx: Context, item: SharedFileItem) {
        try {
            val cleanPreview = item.extractedPreview.replace(Regex("\\s+"), " ").trim().take(120)
            val note = "शेयर/सेव फ़ाइल [${item.kind}]: ${item.name} (${item.timeLabel}) — $cleanPreview"
            MeeraMemoryManager.upsertMemory(ctx.applicationContext, note, category = "file", importance = 2)
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "SQLite memory sync error: ${e.message}")
        }
    }

    fun deleteFile(ctx: Context, itemId: String): Boolean {
        ensureLoaded(ctx)
        val current = filesFlow.value.toMutableList()
        val target = current.firstOrNull { it.id == itemId } ?: return false
        current.remove(target)
        try { File(target.localPath).delete() } catch (_: Exception) {}
        filesFlow.value = current
        if (activeFileFlow.value?.id == itemId) {
            activeFileFlow.value = current.firstOrNull()
            if (activeFileFlow.value == null) chipVisibleFlow.value = false
        }
        persistIndex(ctx.applicationContext, current)
        return true
    }

    fun openInExternalApp(ctx: Context, item: SharedFileItem): Boolean {
        val uri = getShareUri(ctx, item) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.applicationContext.startActivity(intent)
            true
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "openInExternalApp error: ${e.message}")
            false
        }
    }

    fun shareExternally(ctx: Context, item: SharedFileItem, targetPackage: String? = null): Boolean {
        val uri = getShareUri(ctx, item, targetPackage) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!targetPackage.isNullOrBlank()) setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val finalIntent = if (targetPackage.isNullOrBlank()) {
                Intent.createChooser(intent, "फ़ाइल शेयर करें").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else intent
            ctx.applicationContext.startActivity(finalIntent)
            true
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "shareExternally error: ${e.message}")
            false
        }
    }

    fun exportToWorkspace(ctx: Context, item: SharedFileItem): String? {
        val src = File(item.localPath)
        if (!src.exists()) return null
        return try {
            val safeName = item.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(64)
            val primaryDir = File("/sdcard/Download/MeeraWorkspace")
            val targetDir = if (primaryDir.exists() || primaryDir.mkdirs()) {
                primaryDir
            } else {
                File(ctx.applicationContext.getExternalFilesDir(null), "MeeraWorkspace").apply { mkdirs() }
            }
            val dest = File(targetDir, safeName)
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "exportToWorkspace error: ${e.message}")
            null
        }
    }

    fun registerLocalFile(
        ctx: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        kind: String,
        preview: String
    ): SharedFileItem? {
        ensureLoaded(ctx)
        if (!sourceFile.exists()) return null
        return try {
            val app = ctx.applicationContext
            val ts = System.currentTimeMillis()
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(64)
            val dest = File(vaultDir(app), "${ts}_$safeName")
            sourceFile.copyTo(dest, overwrite = true)
            val item = SharedFileItem(
                id = ts.toString(),
                name = displayName,
                mimeType = mimeType,
                kind = kind,
                localPath = dest.absolutePath,
                sizeBytes = dest.length(),
                timestamp = ts,
                timeLabel = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ts)),
                extractedPreview = preview
            )
            val updated = (listOf(item) + filesFlow.value).toMutableList()
            while (updated.size > MAX_ITEMS) {
                val old = updated.removeAt(updated.lastIndex)
                try { File(old.localPath).delete() } catch (_: Exception) {}
            }
            filesFlow.value = updated
            activeFileFlow.value = item
            chipVisibleFlow.value = true
            persistIndex(app, updated)
            recordFileInLongTermMemory(app, item)
            item
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "registerLocalFile error: ${e.message}")
            null
        }
    }

    fun selectActiveFile(item: SharedFileItem) {
        activeFileFlow.value = item
        chipVisibleFlow.value = true
    }

    fun ingestUri(ctx: Context, uri: Uri): SharedFileItem? {
        ensureLoaded(ctx)
        val app = ctx.applicationContext
        return try {
            val cr = app.contentResolver
            var mime = cr.getType(uri)?.trim().orEmpty()
            var displayName = ""
            try {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) displayName = c.getString(0)?.trim().orEmpty()
                }
            } catch (_: Exception) {}

            if (displayName.isBlank()) {
                val seg = uri.lastPathSegment?.substringAfterLast("/")?.trim().orEmpty()
                if (seg.contains(".")) displayName = seg
            }
            val extFromName = displayName.substringAfterLast(".", "").lowercase(Locale.ROOT)
            if (mime.isBlank() || mime == "application/octet-stream") {
                val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extFromName)
                if (!guessed.isNullOrBlank()) mime = guessed
            }
            if (displayName.isBlank()) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.ifBlank { "bin" } ?: "bin"
                displayName = "shared_${SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())}.$ext"
            }

            val kind = detectKind(displayName, mime)
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(64).ifBlank { "file.bin" }
            val ts = System.currentTimeMillis()
            val outFile = File(vaultDir(app), "${ts}_$safeName")

            var totalCopied = 0L
            cr.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { out ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        totalCopied += n
                        if (totalCopied > MAX_COPY_BYTES) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null

            if (totalCopied <= 0L) {
                outFile.delete()
                return null
            }

            val preview = extractPreview(outFile, kind)
            val timeLabel = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ts))
            val item = SharedFileItem(
                id = ts.toString(),
                name = displayName,
                mimeType = mime.ifBlank { defaultMimeForKind(kind) },
                kind = kind,
                localPath = outFile.absolutePath,
                sizeBytes = totalCopied,
                timestamp = ts,
                timeLabel = timeLabel,
                extractedPreview = preview
            )

            val updated = (listOf(item) + filesFlow.value).toMutableList()
            while (updated.size > MAX_ITEMS) {
                val old = updated.removeAt(updated.lastIndex)
                try { File(old.localPath).delete() } catch (_: Exception) {}
            }
            filesFlow.value = updated
            activeFileFlow.value = item
            chipVisibleFlow.value = true
            persistIndex(app, updated)
            recordFileInLongTermMemory(app, item)
            MeeraDebugLog.log("VAULT", "Saved [$kind] $displayName (${totalCopied / 1024} KB)")
            item
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "ingest error: ${e.message}")
            null
        }
    }

    private fun detectKind(name: String, mime: String): String {
        val low = name.lowercase(Locale.ROOT)
        val m = mime.lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp") -> "IMAGE"
            m == "application/pdf" || low.endsWith(".pdf") -> "PDF"
            low.endsWith(".xlsx") || low.endsWith(".csv") || m.contains("spreadsheet") || m.contains("excel") || m == "text/csv" -> "EXCEL"
            low.endsWith(".docx") || m.contains("wordprocessingml") || m.contains("msword") -> "WORD"
            m.startsWith("text/") || low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".json") || low.endsWith(".xml") || low.endsWith(".py") || low.endsWith(".kt") -> "TEXT"
            else -> "OTHER"
        }
    }

    private fun defaultMimeForKind(kind: String): String = when (kind) {
        "IMAGE" -> "image/jpeg"
        "PDF" -> "application/pdf"
        "EXCEL" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "WORD" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "TEXT" -> "text/plain"
        else -> "*/*"
    }

    private fun extractPreview(file: File, kind: String): String {
        return try {
            when (kind) {
                "IMAGE" -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    "Image ${opts.outWidth}x${opts.outHeight} (${file.length() / 1024} KB)"
                }
                "PDF" -> "PDF Document (${file.length() / 1024} KB)"
                "EXCEL" -> if (file.name.lowercase(Locale.ROOT).endsWith(".csv")) file.readText(Charsets.UTF_8).take(1600) else extractXlsxText(file, 1600)
                "WORD" -> extractDocxText(file, 1600)
                "TEXT" -> file.readText(Charsets.UTF_8).take(1600)
                else -> "File (${file.length() / 1024} KB)"
            }
        } catch (_: Exception) {
            "File (${file.length() / 1024} KB)"
        }
    }

    fun extractFullText(item: SharedFileItem, maxChars: Int = 28000): String {
        val f = File(item.localPath)
        if (!f.exists()) return ""
        return try {
            when (item.kind) {
                "EXCEL" -> if (f.name.lowercase(Locale.ROOT).endsWith(".csv")) f.readText(Charsets.UTF_8).take(maxChars) else extractXlsxText(f, maxChars)
                "WORD" -> extractDocxText(f, maxChars)
                "TEXT" -> f.readText(Charsets.UTF_8).take(maxChars)
                else -> item.extractedPreview
            }
        } catch (_: Exception) {
            item.extractedPreview
        }
    }

    private fun extractXlsxText(file: File, maxChars: Int): String {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val n = e.name
                if (!e.isDirectory && (n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")))) {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var tot = 0
                    while (true) {
                        val r = zis.read(buf)
                        if (r <= 0) break
                        tot += r
                        if (tot > 3 * 1024 * 1024) break
                        bos.write(buf, 0, r)
                    }
                    entries[n] = bos.toByteArray()
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val sharedStrings = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var inSi = false
            val cur = StringBuilder()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        if (p.name == "si") { inSi = true; cur.setLength(0) }
                        else if (inSi && p.name == "t") cur.append(p.nextText())
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "si") { sharedStrings.add(cur.toString()); inSi = false }
                    }
                }
                ev = p.next()
            }
        }
        val sb = StringBuilder()
        for (sheetKey in entries.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted().take(3)) {
            val bytes = entries[sheetKey] ?: continue
            sb.append("=== ").append(sheetKey.substringAfterLast("/").removeSuffix(".xml")).append(" ===\n")
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), "UTF-8")
            val rowCells = mutableListOf<String>()
            var cellType = ""
            var cellVal = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && sb.length < maxChars) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> rowCells.clear()
                        "c" -> { cellType = p.getAttributeValue(null, "t").orEmpty(); cellVal = "" }
                        "v" -> {
                            val rawV = p.nextText()
                            cellVal = if (cellType == "s") sharedStrings.getOrElse(rawV.toIntOrNull() ?: -1) { rawV } else rawV
                        }
                        "t" -> if (cellType == "inlineStr") cellVal += p.nextText()
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "c" -> rowCells.add(cellVal.trim())
                        "row" -> if (rowCells.any { it.isNotBlank() }) sb.append(rowCells.joinToString(" | ")).append("\n")
                    }
                }
                ev = p.next()
            }
            sb.append("\n")
        }
        return sb.toString().trim().take(maxChars)
    }

    private fun extractDocxText(file: File, maxChars: Int): String {
        var docBytes: ByteArray? = null
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") { docBytes = zis.readBytes(); break }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val bytes = docBytes ?: return ""
        val sb = StringBuilder()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && sb.length < maxChars) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val tag = p.name.substringAfter(":")
                    if (tag == "t") sb.append(p.nextText())
                    else if (tag == "tab") sb.append("\t")
                    else if (tag == "br") sb.append("\n")
                }
                XmlPullParser.END_TAG -> if (p.name.substringAfter(":") == "p") sb.append("\n")
            }
            ev = p.next()
        }
        return sb.toString().trim().take(maxChars)
    }

    fun getShareUri(ctx: Context, item: SharedFileItem, grantPackage: String? = null): Uri? {
        val f = File(item.localPath)
        if (!f.exists()) return null
        return try {
            val u = FileProvider.getUriForFile(ctx.applicationContext, "${ctx.applicationContext.packageName}.fileprovider", f)
            if (!grantPackage.isNullOrBlank()) {
                try { ctx.applicationContext.grantUriPermission(grantPackage, u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            u
        } catch (e: Exception) {
            MeeraDebugLog.log("VAULT", "FileProvider URI error: ${e.message}")
            null
        }
    }

    fun findFile(ctx: Context, query: String = ""): SharedFileItem? {
        ensureLoaded(ctx)
        val list = filesFlow.value
        if (list.isEmpty()) return null
        val q = query.trim().lowercase(Locale.ROOT)
        val active = activeFileFlow.value
        if (q.isBlank() || q in listOf("latest", "recent", "active", "shared", "current", "file", "फाइल", "फ़ाइल", "abhi", "अभी", "ye", "yeh", "यह")) {
            return active ?: list.first()
        }
        if (q.contains("photo") || q.contains("image") || q.contains("pic") || q.contains("फोटो") || q.contains("इमेज") || q.contains("तस्वीर")) {
            return if (active?.kind == "IMAGE") active else list.firstOrNull { it.kind == "IMAGE" }
        }
        if (q.contains("excel") || q.contains("xlsx") || q.contains("sheet") || q.contains("csv") || q.contains("एक्सेल")) {
            return if (active?.kind == "EXCEL") active else list.firstOrNull { it.kind == "EXCEL" }
        }
        if (q.contains("pdf") || q.contains("पीडीएफ")) {
            return if (active?.kind == "PDF") active else list.firstOrNull { it.kind == "PDF" }
        }
        if (q.contains("word") || q.contains("docx") || q.contains("doc") || q.contains("डॉक")) {
            return if (active?.kind == "WORD") active else list.firstOrNull { it.kind == "WORD" }
        }
        val clean = q.replace(Regex("(?:फाइल|फ़ाइल|file|wali|wala|वाली|वाला|shared|शेयर)"), "").trim()
        if (clean.isNotBlank()) {
            list.firstOrNull { it.name.lowercase(Locale.ROOT).contains(clean) }?.let { return it }
        }
        return active ?: list.first()
    }

    fun compressImageForLiveOrGemini(item: SharedFileItem, maxDim: Int = 1400): ByteArray? {
        if (item.kind != "IMAGE") return null
        val f = File(item.localPath)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bmp.recycle()
            bos.toByteArray()
        } catch (_: Exception) { null }
    }

    fun analyzeFileWithGemini(ctx: Context, item: SharedFileItem, question: String): String {
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) return "पहले Gemini API Key डालें ताकि मैं इस फ़ाइल को पढ़ सकूँ।"
        val prompt = question.trim().ifBlank { "इस फ़ाइल को ध्यान से पढ़ो और मुख्य जानकारी आसान Hinglish में समझाओ।" }
        val parts = JSONArray()
        when (item.kind) {
            "IMAGE" -> {
                val jpg = compressImageForLiveOrGemini(item) ?: return "यह इमेज फ़ाइल पढ़ी नहीं जा सकी।"
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(jpg))))
                parts.put(JSONObject().put("text", "User shared image '${item.name}'. Answer clearly in natural Hinglish:\n$prompt"))
            }
            "PDF" -> {
                val f = File(item.localPath)
                if (!f.exists() || f.length() > 14L * 1024L * 1024L) return "PDF फ़ाइल बहुत बड़ी है या उपलब्ध नहीं है (अधिकतम 14 MB)।"
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", b64(f.readBytes()))))
                parts.put(JSONObject().put("text", "User shared PDF '${item.name}'. Answer clearly in natural Hinglish:\n$prompt"))
            }
            else -> {
                val content = extractFullText(item, 28000)
                if (content.isBlank()) return "फ़ाइल '${item.name}' से टेक्स्ट नहीं निकल पाया।"
                parts.put(JSONObject().put("text", "User shared file '${item.name}' (${item.kind}).\n\n=== CONTENT ===\n$content\n\n=== QUESTION ===\n$prompt\n\nAnswer clearly in natural Hinglish."))
            }
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        var lastErr = ""
        for (model in listOf("gemini-flash-lite-latest", "gemini-3.5-flash", "gemini-3.8-flash")) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val j = JSONObject(resp.body!!.string())
                        val outParts = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (outParts != null) for (i in 0 until outParts.length()) sb.append(outParts.optJSONObject(i)?.optString("text", "") ?: "")
                        if (sb.toString().trim().isNotBlank()) return sb.toString().trim()
                    } else lastErr = "HTTP ${resp.code}"
                }
            } catch (e: Exception) { lastErr = e.message ?: "error" }
        }
        return "फ़ाइल पढ़ने में दिक्कत आई ($lastErr)।"
    }

    fun getVaultSummaryForPrompt(ctx: Context): String {
        ensureLoaded(ctx)
        val list = filesFlow.value
        if (list.isEmpty()) return ""
        val active = activeFileFlow.value ?: list.first()
        val sb = StringBuilder("\n[SHARED FILE VAULT — FILES USER SHARED WITH YOU]:\n")
        sb.append("- ACTIVE FILE: '${active.name}' [${active.kind}] (${active.timeLabel})\n")
        if (list.size > 1) {
            sb.append("- RECENT FILES:\n")
            list.drop(1).take(4).forEach { f -> sb.append("  * '${f.name}' [${f.kind}] (${f.timeLabel})\n") }
        }
        return sb.toString().trimEnd()
    }
}
