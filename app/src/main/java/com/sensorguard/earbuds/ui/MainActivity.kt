package com.sensorguard.earbuds.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sensorguard.earbuds.data.EarbudBatteryState
import com.sensorguard.earbuds.data.GuardRepository
import com.sensorguard.earbuds.service.SensorGuardService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SlateBg = Color(0xFF090E1A)
private val CardSurface = Color(0xFF111827)
private val CardSurfaceAlt = Color(0xFF182235)
private val BorderSubtle = Color(0xFF263248)
private val CyanPrimary = Color(0xFF38BDF8)
private val EmeraldOk = Color(0xFF34D399)
private val AmberWarn = Color(0xFFFBBF24)
private val CoralAlert = Color(0xFFF87171)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)

private val SensorGuardColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF062033),
    secondary = EmeraldOk,
    background = SlateBg,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = CardSurfaceAlt,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = CoralAlert
)

data class PermissionChecklistState(
    val btConnectGranted: Boolean = false,
    val btScanGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val notificationListenerEnabled: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false
) {
    val coreCompletedCount: Int
        get() = listOf(
            btConnectGranted,
            btScanGranted,
            postNotificationsGranted,
            batteryOptimizationIgnored
        ).count { it }
}

class MainActivity : ComponentActivity() {

    private val permissionState = mutableStateOf(PermissionChecklistState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GuardRepository.initialize(applicationContext)
        refreshStatuses()

        setContent {
            MaterialTheme(colorScheme = SensorGuardColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val perms by permissionState
                    SensorGuardDashboard(
                        perms = perms,
                        onPermissionsResult = { refreshStatuses() },
                        onOpenNotificationListenerSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onOpenAppInfoSettings = {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        },
                        onRequestIgnoreBatteryOptimizations = {
                            requestBatteryOptimizationExemption()
                        },
                        onToggleForegroundService = { shouldRun ->
                            val svcIntent = Intent(this, SensorGuardService::class.java).apply {
                                action = if (shouldRun) {
                                    SensorGuardService.ACTION_START
                                } else {
                                    SensorGuardService.ACTION_STOP
                                }
                            }
                            if (shouldRun) {
                                ContextCompat.startForegroundService(this, svcIntent)
                            } else {
                                startService(svcIntent)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        val btConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val btScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notifListener = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        val powerMgr = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = powerMgr?.isIgnoringBatteryOptimizations(packageName) ?: false

        permissionState.value = PermissionChecklistState(
            btConnectGranted = btConnect,
            btScanGranted = btScan,
            postNotificationsGranted = postNotif,
            notificationListenerEnabled = notifListener,
            batteryOptimizationIgnored = batteryIgnored
        )

        GuardRepository.refreshAudioDevicesAndBondedList(applicationContext)
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorGuardDashboard(
    perms: PermissionChecklistState,
    onPermissionsResult: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    onToggleForegroundService: (Boolean) -> Unit
) {
    val guardEnabled by GuardRepository.guardEnabled.collectAsStateWithLifecycle()
    val serviceRunning by GuardRepository.serviceRunning.collectAsStateWithLifecycle()
    val listenerConnected by GuardRepository.listenerConnected.collectAsStateWithLifecycle()
    val resumeDelayMs by GuardRepository.resumeDelayMs.collectAsStateWithLifecycle()
    val rapidPauseCount by GuardRepository.rapidPauseCount.collectAsStateWithLifecycle()
    val rapidPauseWindowSec by GuardRepository.rapidPauseWindowSec.collectAsStateWithLifecycle()
    val pairedDevices by GuardRepository.pairedDevices.collectAsStateWithLifecycle()
    val whitelistedAddresses by GuardRepository.whitelistedAddresses.collectAsStateWithLifecycle()
    val activeA2dpNames by GuardRepository.activeA2dpNames.collectAsStateWithLifecycle()
    val batteryState by GuardRepository.batteryState.collectAsStateWithLifecycle()
    val eventLogs by GuardRepository.eventLogs.collectAsStateWithLifecycle()
    val manualCooldownUntil by GuardRepository.manualCooldownUntilElapsed.collectAsStateWithLifecycle()
    val loopLockoutUntil by GuardRepository.loopLockoutUntilElapsed.collectAsStateWithLifecycle()

    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(manualCooldownUntil, loopLockoutUntil) {
        while (SystemClock.elapsedRealtime() < maxOf(manualCooldownUntil, loopLockoutUntil)) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
        nowElapsed = SystemClock.elapsedRealtime()
    }

    val cooldownSecLeft = ((manualCooldownUntil - nowElapsed) / 1000L).coerceAtLeast(0L)
    val lockoutSecLeft = ((loopLockoutUntil - nowElapsed) / 1000L).coerceAtLeast(0L)

    var showPermissionsExpanded by remember(perms.coreCompletedCount) {
        mutableStateOf(perms.coreCompletedCount < 4)
    }

    val singlePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onPermissionsResult() }

    val anyGuardEngineReady = listenerConnected || serviceRunning

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SensorGuard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Phantom-Pause Shield & BLE Battery",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateBg,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            // 1. Hero Guard Status & Master Controls
            item {
                val (statusLabel, statusColor) = when {
                    !anyGuardEngineReady -> "Tap Start Service to Activate" to AmberWarn
                    !guardEnabled -> "Guard Paused (OFF)" to TextSecondary
                    lockoutSecLeft > 0L -> "Loop Lockout (${lockoutSecLeft}s)" to AmberWarn
                    cooldownSecLeft > 0L -> "Manual Bypass (${cooldownSecLeft}s)" to AmberWarn
                    activeA2dpNames.isEmpty() -> "Standby • Waiting for A2DP" to CyanPrimary
                    listenerConnected -> "Active • MediaSession Shield" to EmeraldOk
                    else -> "Active • AudioFallback Shield" to EmeraldOk
                }

                DashboardCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val a2dpText = if (activeA2dpNames.isEmpty()) {
                                "No Bluetooth A2DP earbuds active"
                            } else {
                                "Output: ${activeA2dpNames.joinToString(", ")}"
                            }
                            Text(
                                text = a2dpText,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Engine: ${if (listenerConnected) "MediaSession" else "AudioPlayback Fallback"} • Service: ${if (serviceRunning) "Running" else "Stopped"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = guardEnabled,
                            onCheckedChange = { GuardRepository.setGuardEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onToggleForegroundService(!serviceRunning) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serviceRunning) CardSurfaceAlt else CyanPrimary,
                                contentColor = if (serviceRunning) TextPrimary else Color(0xFF062033)
                            )
                        ) {
                            Text(
                                text = if (serviceRunning) "Stop Service" else "Start Service",
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                GuardRepository.triggerManualBypassPause(
                                    origin = "In-App Button",
                                    nowElapsedRealtime = SystemClock.elapsedRealtime()
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Text(
                                text = if (cooldownSecLeft > 0L) "Bypass (${cooldownSecLeft}s)" else "Pause (Bypass)",
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            // 2. Battery Card (L, R, Case)
            item {
                BatteryDashboardCard(batteryState = batteryState)
            }

            // 3. Permission Setup Card
            item {
                DashboardCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Permissions & Setup",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${perms.coreCompletedCount}/4 core permissions ready",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (perms.coreCompletedCount == 4) EmeraldOk else AmberWarn
                            )
                        }
                        TextButton(onClick = { showPermissionsExpanded = !showPermissionsExpanded }) {
                            Text(if (showPermissionsExpanded) "Hide" else "Configure")
                        }
                    }

                    if (showPermissionsExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = BorderSubtle)
                        Spacer(modifier = Modifier.height(8.dp))

                        PermissionStepRow(
                            label = "BLUETOOTH_CONNECT",
                            granted = perms.btConnectGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    singlePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                            }
                        )
                        PermissionStepRow(
                            label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                "BLUETOOTH_SCAN"
                            } else {
                                "ACCESS_FINE_LOCATION (BLE)"
                            },
                            granted = perms.btScanGranted,
                            onClick = {
                                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    Manifest.permission.BLUETOOTH_SCAN
                                } else {
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                }
                                singlePermissionLauncher.launch(perm)
                            }
                        )
                        PermissionStepRow(
                            label = "POST_NOTIFICATIONS",
                            granted = perms.postNotificationsGranted,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    singlePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                        PermissionStepRow(
                            label = "Battery Optimization Exemption",
                            granted = perms.batteryOptimizationIgnored,
                            onClick = onRequestIgnoreBatteryOptimizations
                        )
                        PermissionStepRow(
                            label = "Notification Access (Optional — Fallback Active)",
                            granted = perms.notificationListenerEnabled,
                            onClick = onOpenNotificationListenerSettings
                        )
                        if (!perms.notificationListenerEnabled) {
                            TextButton(
                                onClick = onOpenAppInfoSettings,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "Grayed out? Open App Info -> ⋮ -> Allow restricted settings",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanPrimary
                                )
                            }
                        }
                    }
                }
            }

            // 4. Detection Tuning Sliders
            item {
                DashboardCard {
                    Text(
                        text = "Detection & Resume Tuning",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SliderSettingHeader("Auto-Resume Delay", "${resumeDelayMs} ms")
                    Slider(
                        value = resumeDelayMs.toFloat(),
                        onValueChange = { GuardRepository.setResumeDelayMs(it.roundToInt()) },
                        valueRange = 50f..500f
                    )

                    SliderSettingHeader("Rapid-Pause Threshold", "> $rapidPauseCount pauses")
                    Slider(
                        value = rapidPauseCount.toFloat(),
                        onValueChange = { GuardRepository.setRapidPauseCount(it.roundToInt()) },
                        valueRange = 1f..6f,
                        steps = 4
                    )

                    SliderSettingHeader("Rapid-Pause Window", "$rapidPauseWindowSec s")
                    Slider(
                        value = rapidPauseWindowSec.toFloat(),
                        onValueChange = { GuardRepository.setRapidPauseWindowSec(it.roundToInt()) },
                        valueRange = 2f..15f,
                        steps = 12
                    )
                }
            }

            // 5. Device Whitelist Card
            item {
                DashboardCard {
                    Text(
                        text = "Guarded Bluetooth Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (whitelistedAddresses.isEmpty()) {
                            "Empty whitelist — guarding any connected Bluetooth A2DP output."
                        } else {
                            "Guarding ${whitelistedAddresses.size} selected device(s)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = "No paired devices found (grant BLUETOOTH_CONNECT to list).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        pairedDevices.forEach { device ->
                            val isChecked = whitelistedAddresses.contains(device.address)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        GuardRepository.toggleWhitelistedAddress(device.address, checked)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Live Event Log Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Live Event Log (${eventLogs.size}/200)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { GuardRepository.clearLogs() },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            if (eventLogs.isEmpty()) {
                item {
                    DashboardCard {
                        Text(
                            text = "No events yet. Tap Start Service and play audio on your earbuds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                items(eventLogs, key = { it.id }) { item ->
                    val logColor = when {
                        item.message.contains("Resumed") ||
                            item.message.contains("Phantom pause") ||
                            item.message.contains("resumed playback") -> EmeraldOk
                        item.message.contains("LOOP PROTECTION") -> CoralAlert
                        item.message.contains("Manual bypass") -> AmberWarn
                        else -> TextSecondary
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Text(
                            text = "[${item.timestamp}] ${item.message}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = logColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardSurface,
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun BatteryDashboardCard(batteryState: EarbudBatteryState) {
    DashboardCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Earbud Battery",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = CardSurfaceAlt
            ) {
                Text(
                    text = batteryState.modelName,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BatteryTile(
                title = "Left",
                percent = if (batteryState.isReliableModel) batteryState.leftPercent else null,
                charging = batteryState.leftCharging,
                modifier = Modifier.weight(1f)
            )
            BatteryTile(
                title = "Right",
                percent = if (batteryState.isReliableModel) batteryState.rightPercent else null,
                charging = batteryState.rightCharging,
                modifier = Modifier.weight(1f)
            )
            BatteryTile(
                title = "Case",
                percent = if (batteryState.isReliableModel) batteryState.casePercent else null,
                charging = batteryState.caseCharging,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BatteryTile(
    title: String,
    percent: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = CardSurfaceAlt
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = when {
                    percent == null -> "unavailable"
                    charging -> "$percent% ⚡"
                    else -> "$percent%"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    percent == null -> TextSecondary
                    percent <= 20 -> CoralAlert
                    else -> TextPrimary
                }
            )
            LinearProgressIndicator(
                progress = { (percent ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when {
                    percent == null -> BorderSubtle
                    percent <= 20 -> CoralAlert
                    else -> EmeraldOk
                },
                trackColor = SlateBg
            )
        }
    }
}

@Composable
private fun SliderSettingHeader(label: String, valueText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = CyanPrimary
        )
    }
}

@Composable
private fun PermissionStepRow(
    label: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (granted) "Granted / Active" else "Action Required",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) EmeraldOk else AmberWarn
            )
        }
        Button(
            onClick = onClick,
            enabled = !granted,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(if (granted) "Done" else "Grant")
        }
    }
}
