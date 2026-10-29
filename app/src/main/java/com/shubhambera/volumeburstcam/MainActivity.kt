package com.shubhambera.volumeburstcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Bg = Color(0xFF0A0A0A)
private val Card = Color(0xFF121212)
private val Border = Color(0xFF242424)
private val Divider = Color(0xFF1E1E1E)
private val Accent = Color(0xFFFF5A1F)
private val TextPrimary = Color(0xFFECECEC)
private val TextFaint = Color(0xFF686868)
private val TextDim = Color(0xFF6B6B6B)

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Accent)) {
                AppScreen(
                    onRequestCamera = { requestCameraPermission() },
                    onRequestNotifications = { requestNotificationPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onRequestIgnoreBattery = { requestIgnoreBatteryOptimizations() },
                    onOpenAutoStart = { openAutoStartSettings() },
                    hasCameraPermission = { hasCameraPermission() },
                    hasNotificationPermission = { hasNotificationPermission() },
                    isAccessibilityEnabled = { isAccessibilityServiceEnabled() },
                    isIgnoringBattery = { isIgnoringBatteryOptimizations() }
                )
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, VolumeAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(splitter.next())
            if (componentName != null && componentName == expectedComponent) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** MIUI hides a separate "Autostart" toggle outside the normal battery APIs. */
    private fun openAutoStartSettings() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                // Nothing more we can do; user can navigate manually.
            }
        }
    }
}

@Composable
private fun AppScreen(
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onOpenAutoStart: () -> Unit,
    hasCameraPermission: () -> Boolean,
    hasNotificationPermission: () -> Boolean,
    isAccessibilityEnabled: () -> Boolean,
    isIgnoringBattery: () -> Boolean
) {
    val context = LocalContext.current

    var armed by remember { mutableStateOf(SettingsStore.isArmed(context)) }
    var captureType by remember { mutableStateOf(SettingsStore.getCaptureType(context)) }
    var mode by remember { mutableStateOf(SettingsStore.getMode(context)) }
    var shotCount by remember { mutableStateOf(SettingsStore.getShotCount(context)) }
    var roundGap by remember { mutableStateOf(SettingsStore.getRoundGapMs(context)) }
    var pairGap by remember { mutableStateOf(SettingsStore.getPairGapMs(context)) }
    var videoDuration by remember { mutableStateOf(SettingsStore.getVideoDurationMs(context)) }

    // Re-check permission/service status whenever the screen recomposes
    // after returning from a Settings screen.
    var refreshTick by remember { mutableIntStateOf(0) }
    val cameraOk = remember(refreshTick) { hasCameraPermission() }
    val notifOk = remember(refreshTick) { hasNotificationPermission() }
    val accessibilityOk = remember(refreshTick) { isAccessibilityEnabled() }
    val batteryOk = remember(refreshTick) { isIgnoringBattery() }

    val totalShots = if (mode == CameraMode.BOTH) shotCount * 2 else shotCount
    val allReady = cameraOk && notifOk && accessibilityOk

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 34.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Card)
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                )
                Column {
                    Text("Volume Burst Cam", color = Color(0xFFFAFAFA), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Dual-camera synced burst", color = Color(0xFF747474), fontSize = 12.5.sp)
                }
            }

            // Arm card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(20.dp))
                    .border(1.dp, Border, RoundedCornerShape(20.dp))
                    .padding(vertical = 24.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(if (armed) Accent else Color.Transparent)
                        .border(2.dp, if (armed) Accent else Color(0xFF333333), CircleShape)
                        .then(
                            Modifier.clickableNoRipple {
                                if (!allReady) {
                                    refreshTick++
                                    return@clickableNoRipple
                                }
                                armed = !armed
                                SettingsStore.setArmed(context, armed)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (armed) "ON" else "ARM",
                        color = if (armed) Color(0xFF0A0A0A) else Color(0xFFB8B8B8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (armed) "ARMED — LISTENING" else "TAP TO ARM",
                        color = if (armed) Accent else Color(0xFF8A8A8A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        if (captureType == CaptureType.PHOTO)
                            "Press Volume Down ×3 within 1.5s to fire"
                        else
                            "Press Volume Down ×3 within 1.5s to record",
                        color = TextDim,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                if (!allReady) {
                    Text(
                        "Finish setup below before arming",
                        color = Accent,
                        fontSize = 11.sp
                    )
                }
            }

            // Summary row
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (captureType == CaptureType.PHOTO) {
                    SummaryStat(totalShots.toString(), "total shots", Modifier.weight(1f))
                    SummaryStat(formatMs(roundGap), "round gap", Modifier.weight(1f))
                    SummaryStat(
                        if (mode == CameraMode.BOTH) formatMs(pairGap) else "—",
                        "back→front",
                        Modifier.weight(1f)
                    )
                } else {
                    SummaryStat("1", "clip", Modifier.weight(1f))
                    SummaryStat(formatMs(videoDuration), "duration", Modifier.weight(1f))
                    SummaryStat(if (mode == CameraMode.BACK) "Back" else "Front", "camera", Modifier.weight(1f))
                }
            }

            // Setup section
            SectionLabel("Setup")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(16.dp))
                    .border(1.dp, Border, RoundedCornerShape(16.dp))
            ) {
                SetupRow(
                    title = "Camera permission",
                    done = cameraOk,
                    actionLabel = "Grant",
                    onAction = onRequestCamera
                )
                DividerLine()
                SetupRow(
                    title = "Notifications",
                    done = notifOk,
                    actionLabel = "Grant",
                    onAction = onRequestNotifications
                )
                DividerLine()
                SetupRow(
                    title = "Accessibility service",
                    subtitle = "Needed to catch volume presses while locked",
                    done = accessibilityOk,
                    actionLabel = "Open",
                    onAction = onOpenAccessibilitySettings
                )
                DividerLine()
                SetupRow(
                    title = "Battery optimization",
                    subtitle = "Recommended so MIUI doesn't kill the service",
                    done = batteryOk,
                    actionLabel = "Open",
                    onAction = onRequestIgnoreBattery
                )
                DividerLine()
                SetupRow(
                    title = "MIUI autostart",
                    subtitle = "Also enable manually in Security app",
                    done = null,
                    actionLabel = "Open",
                    onAction = onOpenAutoStart
                )
            }

            // Capture settings
            SectionLabel("Capture settings")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Card, RoundedCornerShape(16.dp))
                    .border(1.dp, Border, RoundedCornerShape(16.dp))
            ) {
                // Photo / Video
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text("Capture type", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (captureType == CaptureType.PHOTO) "Burst of still photos" else "A single video clip",
                            color = TextFaint,
                            fontSize = 11.5.sp
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(11.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ModeButton("Photo", captureType == CaptureType.PHOTO, Modifier.weight(1f)) {
                            captureType = CaptureType.PHOTO
                            SettingsStore.setCaptureType(context, captureType)
                        }
                        ModeButton("Video", captureType == CaptureType.VIDEO, Modifier.weight(1f)) {
                            captureType = CaptureType.VIDEO
                            SettingsStore.setCaptureType(context, captureType)
                            // Video has no "Both" option — SettingsStore already
                            // coerces this, just mirror it into local state.
                            mode = SettingsStore.getMode(context)
                        }
                    }
                }
                DividerLine()

                if (captureType == CaptureType.PHOTO) {
                    StepperRow(
                        title = "Capture quantity",
                        subtitle = "Rounds per trigger · $totalShots ${if (totalShots == 1) "image" else "images"} total",
                        valueLabel = shotCount.toString(),
                        onDec = {
                            shotCount = (shotCount - 1).coerceIn(1, 20)
                            SettingsStore.setShotCount(context, shotCount)
                        },
                        onInc = {
                            shotCount = (shotCount + 1).coerceIn(1, 20)
                            SettingsStore.setShotCount(context, shotCount)
                        }
                    )
                    DividerLine()
                    StepperRow(
                        title = "Capture time gap",
                        subtitle = "Delay between each round",
                        valueLabel = formatMs(roundGap),
                        onDec = {
                            roundGap = (roundGap - 100).coerceIn(100, 3000)
                            SettingsStore.setRoundGapMs(context, roundGap)
                        },
                        onInc = {
                            roundGap = (roundGap + 100).coerceIn(100, 3000)
                            SettingsStore.setRoundGapMs(context, roundGap)
                        }
                    )
                    DividerLine()
                    StepperRow(
                        title = "Shot duration",
                        subtitle = if (mode == CameraMode.BOTH) "Back → front switch delay" else "Only used when Both is selected",
                        valueLabel = if (mode == CameraMode.BOTH) formatMs(pairGap) else "—",
                        onDec = {
                            pairGap = (pairGap - 50).coerceIn(0, 2000)
                            SettingsStore.setPairGapMs(context, pairGap)
                        },
                        onInc = {
                            pairGap = (pairGap + 50).coerceIn(0, 2000)
                            SettingsStore.setPairGapMs(context, pairGap)
                        },
                        enabled = mode == CameraMode.BOTH
                    )
                    DividerLine()

                    // Camera mode — Back / Front / Both
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            Text("Camera", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when (mode) {
                                    CameraMode.BOTH -> "Fires back then front each round"
                                    CameraMode.BACK -> "Back camera only"
                                    CameraMode.FRONT -> "Front camera only"
                                },
                                color = TextFaint,
                                fontSize = 11.5.sp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(11.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ModeButton("Back", mode == CameraMode.BACK, Modifier.weight(1f)) {
                                mode = CameraMode.BACK; SettingsStore.setMode(context, mode)
                            }
                            ModeButton("Front", mode == CameraMode.FRONT, Modifier.weight(1f)) {
                                mode = CameraMode.FRONT; SettingsStore.setMode(context, mode)
                            }
                            ModeButton("Both", mode == CameraMode.BOTH, Modifier.weight(1f)) {
                                mode = CameraMode.BOTH; SettingsStore.setMode(context, mode)
                            }
                        }
                    }
                    DividerLine()
                } else {
                    StepperRow(
                        title = "Video duration",
                        subtitle = "Length of the recorded clip",
                        valueLabel = formatMs(videoDuration),
                        onDec = {
                            videoDuration = (videoDuration - 500).coerceIn(1000, 15000)
                            SettingsStore.setVideoDurationMs(context, videoDuration)
                        },
                        onInc = {
                            videoDuration = (videoDuration + 500).coerceIn(1000, 15000)
                            SettingsStore.setVideoDurationMs(context, videoDuration)
                        }
                    )
                    DividerLine()

                    // Camera mode — Back / Front only for video
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            Text("Camera", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (mode == CameraMode.BACK) "Back camera only" else "Front camera only",
                                color = TextFaint,
                                fontSize = 11.5.sp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(11.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ModeButton("Back", mode == CameraMode.BACK, Modifier.weight(1f)) {
                                mode = CameraMode.BACK; SettingsStore.setMode(context, mode)
                            }
                            ModeButton("Front", mode == CameraMode.FRONT, Modifier.weight(1f)) {
                                mode = CameraMode.FRONT; SettingsStore.setMode(context, mode)
                            }
                        }
                    }
                    DividerLine()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Flash", color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                        Text("Always off · no light, no sound", color = TextFaint, fontSize = 11.5.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 26.dp)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(13.dp))
                            .border(1.dp, Border, RoundedCornerShape(13.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4A4A4A))
                        )
                    }
                }
            }

            Text(
                "Works from the lock screen once Accessibility and battery permissions above are granted.",
                color = Color(0xFF5E5E5E),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Card, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(value, color = Color(0xFFFAFAFA), fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = TextDim, fontSize = 10.5.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color(0xFF5E5E5E),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun DividerLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Divider))
}

@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    valueLabel: String,
    onDec: () -> Unit,
    onInc: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextFaint, fontSize = 11.5.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color(0xFF1A1A1A), RoundedCornerShape(10.dp))
                .padding(3.dp)
        ) {
            StepButton("−", enabled, onDec)
            Box(modifier = Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.Center) {
                Text(valueLabel, color = Color(0xFFFAFAFA), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            StepButton("+", enabled, onInc)
        }
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = if (enabled) Color(0xFFC9C9C9) else Color(0xFF444444), fontSize = 17.sp)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent else Color.Transparent)
            .clickableNoRipple(onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0A0A0A) else Color(0xFFB8B8B8),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SetupRow(
    title: String,
    subtitle: String? = null,
    done: Boolean?,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = TextFaint, fontSize = 11.sp)
            }
        }
        if (done == true) {
            Text("Done", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Border, RoundedCornerShape(9.dp))
                    .clickableNoRipple(onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(actionLabel, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatMs(ms: Long): String =
    if (ms < 1000) "${ms}ms" else {
        val seconds = ms / 1000.0
        if (ms % 1000 == 0L) "${seconds.toInt()}s" else "%.1fs".format(seconds)
    }

/** Simple ripple-free clickable modifier to keep the look flat, matching the mockup. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
    )
}
