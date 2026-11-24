package com.meraai.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MeeraFileStudio {
    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun createOrEditFile(
        ctx: Context,
        outputFormat: String,
        instruction: String,
        fileQuery: String = "latest",
        fileTitle: String = ""
    ): ToolExecutionResult {
        val apiKey = Prefs(ctx).apiKey
        if (apiKey.isBlank()) {
            return ToolExecutionResult(true, "create_or_edit_file", "पहले Gemini API Key डालें।", "ERROR: Missing API Key")
        }

        val fmt = normalizeFormat(outputFormat, instruction)
        val sourceItem = if (fileQuery.isNotBlank() && !fileQuery.equals("none", true)) {
            MeeraFileVault.findFile(ctx, fileQuery)
        } else null

        val taskId = MeeraBackgroundMonitor.startTask(
            title = "File Studio ($fmt):${instruction.take(36)}",
            category = "AI File Studio",
            modelUsed = "gemini-3.5-flash",
            initialStep = "डेटा और फ़ॉर्मेट तैयार किया जा रहा है...",
            initialPercent = 15
        )

        return try {
            val aiJson = requestStructuredFilePlan(apiKey, fmt, instruction, sourceItem)
                ?: run {
                    MeeraBackgroundMonitor.completeTask(taskId, false, "AI से स्ट्रक्चर्ड डेटा नहीं मिला")
                    return ToolExecutionResult(
                        true, "create_or_edit_file",
                        "फ़ाइल का डेटा तैयार नहीं हो पाया, एक बार फिर से कोशिश करें।",
                        "FAILED: AI returned empty structured file plan."
                    )
                }

            MeeraBackgroundMonitor.updateProgress(taskId, 65, "$fmt फ़ाइल बनाई जा रही है...")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val rawBase = fileTitle.ifBlank { aiJson.optString("file_name", "meera_file") }
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .trim('_')
                .ifBlank { "meera_file" }
                .take(40)

            val ext = when (fmt) {
                "EXCEL" -> "xlsx"
                "PDF" -> "pdf"
                else -> "docx"
            }
            val finalName = "${rawBase}_$stamp.$ext"
            val tempFile = File(ctx.applicationContext.cacheDir, finalName)

            val summaryNote: String
            val mimeType: String
            when (fmt) {
                "EXCEL" -> {
                    val sheetName = aiJson.optString("sheet_name", "Sheet1").take(28).ifBlank { "Sheet1" }
                    val rows = parseRows(aiJson.optJSONArray("rows"))
                    if (rows.isEmpty()) throw IllegalStateException("Excel के लिए कोई डेटा रो नहीं मिली")
                    buildXlsxFile(tempFile, sheetName, rows)
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    summaryNote = "Excel शीट '$sheetName' (${rows.size} rows, लाइव फ़ॉर्मूला सपोर्ट के साथ)"
                }
                "PDF" -> {
                    val title = aiJson.optString("title", rawBase)
                    val paragraphs = parseStringList(aiJson.optJSONArray("paragraphs"))
                    val tableRows = parseRows(aiJson.optJSONArray("rows"))
                    buildPdfFile(tempFile, title, paragraphs, tableRows)
                    mimeType = "application/pdf"
                    summaryNote = "PDF डॉक्यूमेंट '$title'"
                }
                else -> {
                    val title = aiJson.optString("title", rawBase)
                    val paragraphs = parseStringList(aiJson.optJSONArray("paragraphs"))
                    val tableRows = parseRows(aiJson.optJSONArray("rows"))
                    buildDocxFile(tempFile, title, paragraphs, tableRows)
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    summaryNote = "Word डॉक्यूमेंट '$title'"
                }
            }

            MeeraBackgroundMonitor.updateProgress(taskId, 88, "Downloads और Shared Vault में सेव हो रही है...")
            val savedToDownloads = saveToPublicDownloads(ctx, tempFile, finalName, mimeType)
            val registered = MeeraFileVault.registerLocalFile(
                ctx = ctx,
                sourceFile = tempFile,
                displayName = finalName,
                mimeType = mimeType,
                kind = fmt,
                preview = "$summaryNote (${tempFile.length() / 1024} KB)"
            )
            try { tempFile.delete() } catch (_: Exception) {}

            val dlNote = if (savedToDownloads) "Downloads/$finalName" else finalName
            val cardMsg = "📄 [AI File Studio • $fmt तैयार]:\n• फ़ाइल: $dlNote\n• विवरण: $summaryNote\n• अब आप सीधे बोलकर इसे WhatsApp पर भेज सकते हैं!"
            MeeraAgentFactory.onDeliverToChat?.invoke("File Studio ($fmt)", cardMsg)
            MeeraBackgroundMonitor.completeTask(taskId, true, "100% तैयार — $dlNote", cardMsg)

            ToolExecutionResult(
                true, "create_or_edit_file",
                "हो गया! मैंने $summaryNote बनाकर $dlNote में सेव कर दिया है और इसे एक्टिव फ़ाइल बना दिया है। अब चाहें तो सीधे WhatsApp पर भेज दूँ?",
                "VERIFIED_SUCCESS: Created $fmt file '$dlNote' (vault_id=${registered?.id ?: "none"})."
            )
        } catch (e: Exception) {
            MeeraDebugLog.log("FILE_STUDIO", "Error: ${e.message}")
            MeeraBackgroundMonitor.completeTask(taskId, false, "एरर: ${e.message}")
            ToolExecutionResult(
                true, "create_or_edit_file",
                "फ़ाइल बनाने में दिक्कत आई: ${e.message}",
                "ERROR: File Studio failed -> ${e.message}"
            )
        }
    }

    private fun normalizeFormat(outputFormat: String, instruction: String): String {
        val f = outputFormat.trim().uppercase(Locale.ROOT)
        val low = instruction.lowercase(Locale.ROOT)
        return when {
            f.contains("XLS") || f.contains("EXCEL") || f.contains("CSV") || f.contains("SHEET") -> "EXCEL"
            f.contains("PDF") -> "PDF"
            f.contains("DOC") || f.contains("WORD") -> "WORD"
            low.contains("excel") || low.contains("xlsx") || low.contains("एक्सेल") || low.contains("शीट") -> "EXCEL"
            low.contains("pdf") || low.contains("पीडीएफ") -> "PDF"
            else -> "WORD"
        }
    }

    private fun requestStructuredFilePlan(
        apiKey: String,
        fmt: String,
        instruction: String,
        sourceItem: SharedFileItem?
    ): JSONObject? {
        val schemaPrompt = if (fmt == "EXCEL") {
            "Return ONLY valid JSON: {\"file_name\":\"name\",\"sheet_name\":\"Sheet1\",\"rows\":[[\"Header1\",\"Qty\",\"Rate\",\"Total\"],[\"Item\",10,50,\"=B2*C2\"],[\"Total\",\"\",\"\",\"=SUM(D2:D2)\"]]}. First row is headers. Use real Excel formulas starting with '=' where calculations/totals are needed."
        } else {
            "Return ONLY valid JSON: {\"file_name\":\"name\",\"title\":\"Title\",\"paragraphs\":[\"Paragraph 1\"],\"rows\":[[\"Col1\",\"Col2\"]]}. Put structured text in paragraphs and tables in rows."
        }

        val parts = JSONArray()
        if (sourceItem != null) {
            when (sourceItem.kind) {
                "IMAGE" -> {
                    MeeraFileVault.compressImageForLiveOrGemini(sourceItem, 1600)?.let { jpg ->
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(jpg))))
                    }
                }
                "PDF" -> {
                    val f = File(sourceItem.localPath)
                    if (f.exists() && f.length() <= 14L * 1024L * 1024L) {
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", b64(f.readBytes()))))
                    }
                }
                else -> {
                    val txt = MeeraFileVault.extractFullText(sourceItem, 24000)
                    if (txt.isNotBlank()) {
                        parts.put(JSONObject().put("text", "EXISTING SOURCE FILE '${sourceItem.name}' (${sourceItem.kind}):\n$txt"))
                    }
                }
            }
        }
        parts.put(JSONObject().put("text", "$schemaPrompt\n\nUSER INSTRUCTION:\n$instruction"))

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))

        for (model in listOf("gemini-3.5-flash", "gemini-3.8-flash", "gemini-flash-lite-latest")) {
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
                        if (outParts != null) {
                            for (i in 0 until outParts.length()) {
                                sb.append(outParts.optJSONObject(i)?.optString("text", "") ?: "")
                            }
                        }
                        val clean = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                        if (clean.startsWith("{")) return JSONObject(clean)
                    }
                }
            } catch (e: Exception) {
                MeeraDebugLog.log("FILE_STUDIO", "Model $model failed: ${e.message}")
            }
        }
        return null
    }

    private fun parseRows(arr: JSONArray?): List<List<String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<List<String>>()
        for (i in 0 until arr.length()) {
            val rowArr = arr.optJSONArray(i) ?: continue
            val cells = mutableListOf<String>()
            for (c in 0 until rowArr.length()) {
                val v = rowArr.opt(c)
                cells.add(if (v == null || v == JSONObject.NULL) "" else v.toString())
            }
            if (cells.isNotEmpty()) out.add(cells)
        }
        return out
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    private fun xmlEsc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun colLetter(idx: Int): String {
        var n = idx + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun buildXlsxFile(outFile: File, sheetName: String, rows: List<List<String>>) {
        val safeSheet = xmlEsc(sheetName.replace(Regex("[\\\\/*?:\\[\\]]"), " ").trim().ifBlank { "Sheet1" })
        val sheetXml = StringBuilder()
        sheetXml.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sheetXml.append("""<worksheet xmlns="[http://schemas.openxmlformats.org/spreadsheetml/2006/main](http://schemas.openxmlformats.org/spreadsheetml/2006/main)"><sheetData>""")
        for ((rIdx, row) in rows.withIndex()) {
            val rNum = rIdx + 1
            sheetXml.append("""<row r="$rNum">""")
            for ((cIdx, rawVal) in row.withIndex()) {
                val ref = "${colLetter(cIdx)}$rNum"
                val styleAttr = if (rIdx == 0) " s=\"1\"" else ""
                val trimmed = rawVal.trim()
                val num = trimmed.toDoubleOrNull()
                if (trimmed.startsWith("=") && trimmed.length > 1) {
                    val f = xmlEsc(trimmed.substring(1))
                    sheetXml.append("""<c r="$ref"$styleAttr><f>$f</f></c>""")
                } else if (num != null && (!trimmed.startsWith("0") || trimmed == "0")) {
                    sheetXml.append("""<c r="$ref"$styleAttr><v>$trimmed</v></c>""")
                } else {
                    sheetXml.append("""<c r="$ref"$styleAttr t="inlineStr"><is><t>${xmlEsc(rawVal)}</t></is></c>""")
                }
            }
            sheetXml.append("</row>")
        }
        sheetXml.append("</sheetData></worksheet>")

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="[http://schemas.openxmlformats.org/package/2006/content-types](http://schemas.openxmlformats.org/package/2006/content-types)">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="[http://schemas.openxmlformats.org/package/2006/relationships](http://schemas.openxmlformats.org/package/2006/relationships)">
  <Relationship Id="rId1" Type="[http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument](http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument)" Target="xl/workbook.xml"/>
</Relationships>"""

        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="[http://schemas.openxmlformats.org/spreadsheetml/2006/main](http://schemas.openxmlformats.org/spreadsheetml/2006/main)" xmlns:r="[http://schemas.openxmlformats.org/officeDocument/2006/relationships](http://schemas.openxmlformats.org/officeDocument/2006/relationships)">
  <sheets><sheet name="$safeSheet" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="[http://schemas.openxmlformats.org/package/2006/relationships](http://schemas.openxmlformats.org/package/2006/relationships)">
  <Relationship Id="rId1" Type="[http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet](http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet)" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="[http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles](http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles)" Target="styles.xml"/>
</Relationships>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="[http://schemas.openxmlformats.org/spreadsheetml/2006/main](http://schemas.openxmlformats.org/spreadsheetml/2006/main)">
  <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>
</styleSheet>"""

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            fun put(name: String, data: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", wbRels)
            put("xl/styles.xml", styles)
            put("xl/worksheets/sheet1.xml", sheetXml.toString())
        }
    }

    private fun buildPdfFile(outFile: File, title: String, paragraphs: List<String>, rows: List<List<String>>) {
        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 42f
        val maxW = pageW - margin * 2

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 11.5f
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1D4ED8")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = page.canvas
        var y = 56f

        fun ensureSpace(need: Float) {
            if (y + need > pageH - 44f) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = 50f
            }
        }

        fun drawWrapped(text: String, paint: Paint, lineH: Float) {
            val words = text.split(Regex("\\s+"))
            var line = ""
            for (w in words) {
                val cand = if (line.isEmpty()) w else "$line $w"
                if (paint.measureText(cand) <= maxW) {
                    line = cand
                } else {
                    ensureSpace(lineH)
                    canvas.drawText(line, margin, y, paint)
                    y += lineH
                    line = w
                }
            }
            if (line.isNotEmpty()) {
                ensureSpace(lineH)
                canvas.drawText(line, margin, y, paint)
                y += lineH
            }
        }

        drawWrapped(title, titlePaint, 24f)
        y += 8f
        for (p in paragraphs) {
            drawWrapped(p, bodyPaint, 16f)
            y += 6f
        }
        if (rows.isNotEmpty()) {
            y += 6f
            rows.forEachIndexed { idx, row ->
                val lineText = row.joinToString("  |  ")
                drawWrapped(lineText, if (idx == 0) headerPaint else bodyPaint, 16f)
                y += 3f
            }
        }
        doc.finishPage(page)
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
    }

    private fun buildDocxFile(outFile: File, title: String, paragraphs: List<String>, rows: List<List<String>>) {
        val body = StringBuilder()
        body.append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>${xmlEsc(title)}</w:t></w:r></w:p>""")
        for (p in paragraphs) {
            body.append("""<w:p><w:r><w:t xml:space="preserve">${xmlEsc(p)}</w:t></w:r></w:p>""")
        }
        for (row in rows) {
            body.append("""<w:p><w:r><w:t xml:space="preserve">${xmlEsc(row.joinToString(" | "))}</w:t></w:r></w:p>""")
        }

        val docXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="[http://schemas.openxmlformats.org/wordprocessingml/2006/main](http://schemas.openxmlformats.org/wordprocessingml/2006/main)"><w:body>$body</w:body></w:document>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="[http://schemas.openxmlformats.org/package/2006/content-types](http://schemas.openxmlformats.org/package/2006/content-types)">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="[http://schemas.openxmlformats.org/package/2006/relationships](http://schemas.openxmlformats.org/package/2006/relationships)">
  <Relationship Id="rId1" Type="[http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument](http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument)" Target="word/document.xml"/>
</Relationships>"""

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            fun put(name: String, data: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("word/document.xml", docXml)
        }
    }

    private fun saveToPublicDownloads(ctx: Context, file: File, displayName: String, mimeType: String): Boolean {
        return try {
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
                app.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                file.copyTo(File(dir, displayName), overwrite = true)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
