package dev.jasonpearson.motiondots.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jasonpearson.motiondots.net.ConnectionState
import dev.jasonpearson.motiondots.net.DiscoveredMac
import dev.jasonpearson.motiondots.net.HostPort
import dev.jasonpearson.motiondots.stream.StreamingController
import dev.jasonpearson.motiondots.stream.StreamingService
import java.util.Locale

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val streaming by StreamingController.streaming.collectAsStateWithLifecycle()
    val connection by StreamingController.connection.collectAsStateWithLifecycle()
    val target by StreamingController.target.collectAsStateWithLifecycle()
    val achievedHz by StreamingController.achievedHz.collectAsStateWithLifecycle()
    val dropped by StreamingController.dropped.collectAsStateWithLifecycle()
    val accel by StreamingController.linearAccel.collectAsStateWithLifecycle()
    val message by StreamingController.message.collectAsStateWithLifecycle()
    val macs by viewModel.macs.collectAsStateWithLifecycle()
    val lastMac by viewModel.lastMac.collectAsStateWithLifecycle()

    var manualText by rememberSaveable { mutableStateOf("") }
    var manualError by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    KeepScreenOn(streaming)

    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Streaming works without the notification being visible, so proceed either way.
            pendingStart?.invoke()
            pendingStart = null
        }

    fun startWithPermission(action: () -> Unit) {
        StreamingController.setMessage(null)
        val needs =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        if (needs) {
            pendingStart = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    fun connect(mac: DiscoveredMac) = startWithPermission { StreamingService.start(context, mac) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier.safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("MotionDots", style = MaterialTheme.typography.headlineMedium)

            StatusCard(
                connection = connection,
                streaming = streaming,
                target = target,
                achievedHz = achievedHz,
                dropped = dropped,
                message = message,
            )

            Button(
                onClick = {
                    if (streaming) {
                        StreamingService.stop(context)
                    } else {
                        val explicit =
                            target ?: lastMac ?: macs.singleOrNull()
                        startWithPermission {
                            if (explicit != null) StreamingService.start(context, explicit)
                            else StreamingService.startAuto(context)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                colors =
                    if (streaming) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
            ) {
                Text(if (streaming) "Stop" else "Start", fontSize = 22.sp)
            }

            AccelCard(accel)

            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Macs on this network", style = MaterialTheme.typography.titleMedium)
                    if (macs.isEmpty()) {
                        Text(
                            "Searching... Make sure MotionDots is running on your Mac and both are on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    macs.forEach { mac ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(mac.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${mac.host}:${mac.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { connect(mac) }) { Text("Connect") }
                        }
                    }
                }
            }

            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Manual connection", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = manualText,
                        onValueChange = {
                            manualText = it
                            manualError = false
                        },
                        label = { Text("host:port, e.g. 192.168.1.20:52000") },
                        singleLine = true,
                        isError = manualError,
                        supportingText = if (manualError) ({ Text("Enter host:port") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val hp = HostPort.parse(manualText)
                            if (hp == null) {
                                manualError = true
                            } else {
                                connect(DiscoveredMac(hp.host, hp.host, hp.port))
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Connect")
                    }
                }
            }

            BatteryRow(context)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(
    connection: ConnectionState,
    streaming: Boolean,
    target: DiscoveredMac?,
    achievedHz: Float,
    dropped: Long,
    message: String?,
) {
    val statusText =
        when (connection) {
            ConnectionState.Idle -> if (streaming) "Starting" else "Not streaming"
            is ConnectionState.Connecting -> "Connecting to ${connection.host}"
            is ConnectionState.Connected -> "Connected to ${connection.hostName}"
            is ConnectionState.Reconnecting -> "Reconnecting (attempt ${connection.attempt})"
        }
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(statusText, style = MaterialTheme.typography.titleLarge)
            Text(
                "Mac: " + (target?.let { "${it.name} (${it.host}:${it.port})" } ?: "none selected"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Rate: ${String.format(Locale.US, "%.1f", achievedHz)} Hz   Dropped: $dropped",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AccelCard(accel: FloatArray) {
    Card(
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Linear acceleration (m/s²)", style = MaterialTheme.typography.titleMedium)
            val labels = listOf("ax", "ay", "az")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0 until 3) {
                    Text(
                        "${labels[i]} ${String.format(Locale.US, "%+6.2f", accel.getOrElse(i) { 0f })}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Text(
                "Push the phone to the right: X goes positive",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryRow(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Battery optimization", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ignoring) "Unrestricted: streaming can run in the background"
                else "Optimized: Android may pause streaming in the background",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ) {
            Text("Open")
        }
    }
}

/** Sets FLAG_KEEP_SCREEN_ON on the window while [enabled]. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
