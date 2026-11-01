package com.meraai.app

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// Option 4: Mint Sage Frost Palette
private val MintBg = Color(0xFFEAF6F0)
private val MintCard = Color(0xFFF4FBF8)
private val MintCardBorder = Color(0xFFD5EBE0)
private val MintTextPrimary = Color(0xFF133A2B)
private val MintTextSecondary = Color(0xFF3F6A58)
private val MintAccentStart = Color(0xFF059669)
private val MintAccentEnd = Color(0xFF047857)
private val MintIconBg = Color(0xFFD1FAE5)
private val MintIconTint = Color(0xFF047857)
private val BadgeWaitBg = Color(0xFFFEF3C7)
private val BadgeWaitText = Color(0xFFB45309)
private val BadgeOkBg = Color(0xFFBBF7D0)
private val BadgeOkText = Color(0xFF166534)

data class RuntimePermGroup(
    val id: String,
    val icon: String,
    val title: String,
    val desc: String,
    val perms: List<String>
)

object PermHelper {
    fun getStep1Groups(): List<RuntimePermGroup> {
        val galleryPerms = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val callHandlePerms = buildList {
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 26) add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        val btPerms = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH)
        }

        return listOf(
            RuntimePermGroup("mic", "🎙️", "Microphone", "Taaki tum Meera se baat kar sako. Zaroori hai.", listOf(Manifest.permission.RECORD_AUDIO)),
            RuntimePermGroup("cam", "📷", "Camera", "Taaki wo photo le sake ya jo tum phone se dikhao wo dekh sake.", listOf(Manifest.permission.CAMERA)),
            RuntimePermGroup("call", "📞", "Phone calls", "Taaki wo tumhare liye call laga sake.", listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)),
            RuntimePermGroup("contacts", "👤", "Contacts", "Taaki sirf naam kaafi ho — number wo khud dhoondh legi.", listOf(Manifest.permission.READ_CONTACTS)),
            RuntimePermGroup("sms", "💬", "SMS", "Taaki wo text message bhej aur padh sake.", listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)),
            RuntimePermGroup("loc", "📍", "Location", "Mausam, raasta, aur tum kahan ho.", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            RuntimePermGroup("gallery", "🖼️", "Gallery aur files", "Taaki wo koi photo ya file dhoondh kar bhej sake.", galleryPerms),
            RuntimePermGroup("call_ans", "🎧", "Call uthana aur sambhalna", "Taaki wo bata sake kaun call kar raha hai aur call utha ya kaat sake.", callHandlePerms),
            RuntimePermGroup("bt", "🦷", "Bluetooth", "Taaki uski aawaz tumhare earbuds ya speaker par aaye.", btPerms)
        )
    }

    fun isGroupGranted(ctx: Context, perms: List<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun isMicGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun isBatteryIgnored(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) { false }

    fun isOverlayGranted(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (_: Exception) { false }

    fun isNotificationListenerEnabled(ctx: Context): Boolean = try {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
        flat.contains(ctx.packageName)
    } catch (_: Exception) { false }

    fun isAccessibilityEnabled(ctx: Context): Boolean = try {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val cn = ComponentName(ctx, MeeraAccessibilityService::class.java).flattenToString()
        val cnShort = ComponentName(ctx, MeeraAccessibilityService::class.java).flattenToShortString()
        flat.contains(cn, ignoreCase = true) || flat.contains(cnShort, ignoreCase = true) || flat.contains(ctx.packageName, ignoreCase = true)
    } catch (_: Exception) { false }

    fun isDefaultAssistant(ctx: Context): Boolean = try {
        val cur = Settings.Secure.getString(ctx.contentResolver, "assistant") ?: ""
        val roleHeld = if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else false
        roleHeld || cur.contains(ctx.packageName, ignoreCase = true)
    } catch (_: Exception) { false }

    fun openBatteryOptimization(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    fun openOverlaySettings(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {}
    }

    fun openNotificationListenerSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun openAccessibilitySettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun openDefaultAssistantSettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }
}

@Composable
fun PermissionsSetupScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var step by remember { mutableIntStateOf(1) }
    var refreshTick by remember { mutableIntStateOf(0) }

    // जब भी यूज़र मोबाइल की Settings से Allow करके वापस आए, तुरंत स्टेटस रिफ्रेश हो जाए
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTick++
    }

    val step1Groups = remember { PermHelper.getStep1Groups() }
    val micOk = remember(refreshTick) { PermHelper.isMicGranted(ctx) }
    val batteryOk = remember(refreshTick) { PermHelper.isBatteryIgnored(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MintBg)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // ---------- Top Header (App Logo + Title + Step) ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(MintAccentStart, MintAccentEnd))),
                contentAlignment = Alignment.Center
            ) {
                Text("M", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Meera set up karo", color = MintTextPrimary, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("Step $step / 2", color = MintTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (step == 2) {
                Text(
                    "← पीछें",
                    color = MintIconTint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { step = 1 }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------- 2-Segment Progress Bar ----------
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MintAccentStart)
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (step == 2) MintAccentStart else MintCardBorder)
            )
        }

        Spacer(Modifier.height(18.dp))

        // ---------- Main Heading & Subtitle ----------
        Text(
            text = if (step == 1) "Meera ko apna phone use karne do" else "Meera ko background me zinda rakho",
            color = MintTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (step == 1) {
                "Ek tap — Android ek-ek karke sab poochega. Sirf microphone zaroori hai; baaki se wo kaam ki banti hai."
            } else {
                "Battery zaroori hai — isi se screen off hone par wo chup nahi hoti. 'Baaki hai' wali row par tap karo, uski system screen khulegi."
            },
            color = MintTextSecondary,
            fontSize = 13.5.sp,
            lineHeight = 19.sp
        )

        Spacer(Modifier.height(14.dp))

        // ---------- Scrollable Permission Cards ----------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step == 1) {
                step1Groups.forEach { grp ->
                    val granted = remember(refreshTick) { PermHelper.isGroupGranted(ctx, grp.perms) }
                    MintPermCard(
                        icon = grp.icon,
                        title = grp.title,
                        desc = grp.desc,
                        granted = granted,
                        onClick = {
                            multiLauncher.launch(grp.perms.toTypedArray())
                        }
                    )
                }
            } else {
                val overlayOk = remember(refreshTick) { PermHelper.isOverlayGranted(ctx) }
                val notifOk = remember(refreshTick) { PermHelper.isNotificationListenerEnabled(ctx) }
                val a11yOk = remember(refreshTick) { PermHelper.isAccessibilityEnabled(ctx) }
                val assistOk = remember(refreshTick) { PermHelper.isDefaultAssistant(ctx) }

                MintPermCard(
                    icon = "🔋",
                    title = "Battery — optimisation band",
                    desc = "Zaroori hai. Iske bina phone background me Meera ko band kar deta hai aur wo chup ho jaati hai. Tap karo, phir Allow chuno.",
                    granted = batteryOk,
                    onClick = { PermHelper.openBatteryOptimization(ctx) }
                )
                MintPermCard(
                    icon = "💠",
                    title = "Dusre apps ke upar dikhna",
                    desc = "Taaki uska orb tum jo bhi kar rahe ho uske upar tair sake.",
                    granted = overlayOk,
                    onClick = { PermHelper.openOverlaySettings(ctx) }
                )
                MintPermCard(
                    icon = "🔔",
                    title = "Notification access",
                    desc = "WhatsApp messages padhne aur kaun call kar raha hai ye jaanne ke liye. List me 'Meera AI' on karo.",
                    granted = notifOk,
                    onClick = { PermHelper.openNotificationListenerSettings(ctx) }
                )
                MintPermCard(
                    icon = "🚹",
                    title = "Accessibility service",
                    desc = "Taaki wo apps khol sake, tap kar sake aur tumhare liye screen padh sake. List me 'Meera AI' on karo.",
                    granted = a11yOk,
                    onClick = { PermHelper.openAccessibilitySettings(ctx) }
                )
                MintPermCard(
                    icon = "👑",
                    title = "Default assistant",
                    desc = "Power button long-press karo ya kone se swipe karo aur use bulao, lock screen par bhi. 'Meera AI' chuno.",
                    granted = assistOk,
                    onClick = { PermHelper.openDefaultAssistantSettings(ctx) }
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))

        // ---------- Bottom Action Buttons ----------
        if (step == 1) {
            // Primary Button: ✓ Sab allow karo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(MintAccentStart, MintAccentEnd)))
                    .clickable {
                        val allPerms = buildList {
                            step1Groups.forEach { addAll(it.perms) }
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.distinct()
                        multiLauncher.launch(allPerms.toTypedArray())
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("✓  Sab allow karo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(Modifier.height(10.dp))

            // Secondary Button: → Aage badho
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .shadow(4.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(MintCard)
                    .border(1.dp, MintCardBorder, RoundedCornerShape(18.dp))
                    .clickable {
                        if (micOk) {
                            step = 2
                        } else {
                            multiLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "→  Aage badho",
                    color = if (micOk) MintTextPrimary else MintTextSecondary.copy(alpha = 0.6f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = if (micOk) "Microphone allow ho chuka hai! 'Aage badho' dabayein." else "Microphone allow hote hi 'Aage badho' khul jayega.",
                color = MintTextSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        } else {
            // Step 2 Finish Button: ✓ Ho gaya
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(8.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(MintAccentStart, MintAccentEnd)))
                    .clickable {
                        if (!batteryOk) {
                            PermHelper.openBatteryOptimization(ctx)
                        } else {
                            onFinish()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (batteryOk) "✓  Ho gaya (Meera Shuru Karein)" else "🔋 Pehle Battery Allow Karein",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Abhi skip karke seedhe Meera par jayein →",
                color = MintIconTint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFinish() }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun MintPermCard(
    icon: String,
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(5.dp, RoundedCornerShape(22.dp), ambientColor = Color(0xFF94B8A8), spotColor = Color(0xFF94B8A8))
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, if (granted) BadgeOkBg else MintCardBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        color = MintCard,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MintIconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 20.sp)
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MintTextPrimary,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = desc,
                    color = MintTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (granted) BadgeOkBg else BadgeWaitBg)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (granted) "Ho gaya ✓" else "Baaki hai",
                    color = if (granted) BadgeOkText else BadgeWaitText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
