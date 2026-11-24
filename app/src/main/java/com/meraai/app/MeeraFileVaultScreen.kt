package com.meraai.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun MeeraFileVaultOverlay(vm: VoiceViewModel) {
    val isOpen by MeeraFileVault.vaultScreenOpenFlow.collectAsState()
    if (!isOpen) return

    val ctx = LocalContext.current
    val p = LocalMeeraColors.current
    val files by MeeraFileVault.filesFlow.collectAsState()
    val activeFile by MeeraFileVault.activeFileFlow.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") }
    var studioPrompt by remember { mutableStateOf("") }

    LaunchedEffect(isOpen) {
        MeeraFileVault.ensureLoaded(ctx)
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.onSharedUrisReceived(uris)
    }

    val filtered = remember(files, selectedFilter) {
        if (selectedFilter == "ALL") files else files.filter { it.kind == selectedFilter }
    }

    Dialog(
        onDismissRequest = { MeeraFileVault.closeVaultScreen() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.panel,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "File Vault & AI Studio",
                            color = p.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${files.size} फ़ाइलें सेव हैं • SQLite Memory सिंक चालू",
                            color = p.textSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = p.accentPrimary.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { attachLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = "अटैच", tint = p.accentPrimary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Attach", color = p.accentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(p.card)
                                .border(1.dp, p.cardBorder, CircleShape)
                                .clickable { MeeraFileVault.closeVaultScreen() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "बंद करें", tint = p.textPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Quick AI File Studio Creator
                Surface(
                    color = p.card,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, p.cardBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "AI File Studio (तुरंत नई फ़ाइल बनाएँ)",
                            color = p.accentPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = studioPrompt,
                            onValueChange = { studioPrompt = it },
                            placeholder = { Text("जैसे: Monthly expense sheet with GST & Total...", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("EXCEL" to "+ Excel", "PDF" to "+ PDF", "WORD" to "+ Word").forEach { (fmt, label) ->
                                Surface(
                                    color = p.accentSuccess.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val q = studioPrompt.trim()
                                            if (q.isBlank()) {
                                                Toast.makeText(ctx, "पहले ऊपर फ़ाइल का विवरण लिखें", Toast.LENGTH_SHORT).show()
                                            } else {
                                                studioPrompt = ""
                                                MeeraFileVault.closeVaultScreen()
                                                vm.sendTextQuery("AI File Studio से $fmt फ़ाइल बनाओ: $q", speakAloud = true)
                                            }
                                        }
                                ) {
                                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, color = p.accentSuccess, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("ALL", "IMAGE", "EXCEL", "PDF", "WORD").forEach { kind ->
                        val sel = selectedFilter == kind
                        Surface(
                            color = if (sel) p.accentPrimary else p.card,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .border(1.dp, if (sel) p.accentPrimary else p.cardBorder, RoundedCornerShape(12.dp))
                                .clickable { selectedFilter = kind }
                        ) {
                            Text(
                                kind,
                                color = if (sel) Color.White else p.textSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = p.cardBorder)
                Spacer(Modifier.height(8.dp))

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "इस कैटेगरी में अभी कोई फ़ाइल नहीं है।\nऊपर Attach दबाएँ या किसी ऐप से Share to Meera AI करें।",
                            color = p.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            val isActive = activeFile?.id == item.id
                            val icon = when (item.kind) {
                                "IMAGE" -> Icons.Rounded.Image
                                "EXCEL" -> Icons.Rounded.TableChart
                                else -> Icons.Rounded.Description
                            }
                            Surface(
                                color = p.card,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (isActive) p.accentPrimary else p.cardBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(p.accentPrimary.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(icon, contentDescription = null, tint = p.accentPrimary, modifier = Modifier.size(18.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.name,
                                                color = p.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${item.kind} • ${(item.sizeBytes / 1024).coerceAtLeast(1)} KB • ${item.timeLabel}",
                                                color = p.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (isActive) {
                                            Surface(
                                                color = p.accentSuccess.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    "ACTIVE",
                                                    color = p.accentSuccess,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = p.accentPrimary.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                MeeraFileVault.selectActiveFile(item)
                                                Toast.makeText(ctx, "एक्टिव फ़ाइल सेट: ${item.name}", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Active", color = p.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                        }

                                        Surface(
                                            color = p.accentSecondary.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                if (!MeeraFileVault.openInExternalApp(ctx, item)) {
                                                    Toast.makeText(ctx, "इस फ़ाइल को खोलने वाला ऐप नहीं मिला", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        ) {
                                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = p.accentSecondary, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("Open", color = p.accentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Surface(
                                            color = p.accentSuccess.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                MeeraFileVault.shareExternally(ctx, item)
                                            }
                                        ) {
                                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.Share, contentDescription = null, tint = p.accentSuccess, modifier = Modifier.size(13.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Text("Share", color = p.accentSuccess, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        Surface(
                                            color = p.accentPrimary.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                MeeraFileVault.selectActiveFile(item)
                                                MeeraFileVault.closeVaultScreen()
                                                vm.sendTextQuery("इस फ़ाइल '${item.name}' को ध्यान से पढ़कर संक्षेप में समझाओ", speakAloud = true)
                                            }
                                        ) {
                                            Text("AI पढ़ो", color = p.accentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                        }

                                        Spacer(Modifier.weight(1f))

                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "हटाएँ",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clickable {
                                                    MeeraFileVault.deleteFile(ctx, item.id)
                                                    Toast.makeText(ctx, "फ़ाइल हटाई गई", Toast.LENGTH_SHORT).show()
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
