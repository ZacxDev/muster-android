package dev.zacx.muster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.KeyKind
import dev.zacx.muster.core.auth.PushStatus
import kotlinx.coroutines.flow.Flow

/** First run: the muster server URL. The repo carries no deployment's address. */
@Composable
fun ServerUrlScreen(onSubmit: (String) -> Boolean) {
    var url by remember { mutableStateOf("https://") }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("muster", style = MaterialTheme.typography.headlineMedium)
            Text("Enter your muster server's address. The app opens only that address; other links open in your browser.")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { if (!onSubmit(url)) error = "Enter an https:// address, with no user name or password." }) {
                Text("Continue")
            }
        }
    }
}

/** Shown above the shell only when something needs the user. */
@Composable
fun StatusBanner(records: Flow<DeviceRecord>, keyInvalidated: suspend () -> Boolean, onDetails: () -> Unit) {
    val r by records.collectAsState(initial = DeviceRecord())
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(r.keyKind) { invalid = keyInvalidated() }
    val text = when {
        !r.paired -> null
        invalid -> "Re-confirm this phone: its step-up key is gone (a new fingerprint or face was enrolled). Open Account → Devices and tap Pair this phone."
        r.pushStatus in setOf(PushStatus.HOST_MISMATCH, PushStatus.NO_DISTRIBUTOR, PushStatus.REFUSED, PushStatus.UNREGISTERED) -> r.pushNote
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

/** The native phone settings: what the phone can do, and why not. */
@Composable
fun PhoneSettingsScreen(
    records: Flow<DeviceRecord>,
    keyInvalidated: suspend () -> Boolean,
    notificationsEnabled: () -> Boolean,
    onNotificationSettings: () -> Unit,
    onRetryPush: () -> Unit,
    onUnpair: () -> Unit,
    onChangeServer: () -> Unit,
    onClose: () -> Unit,
) {
    val r by records.collectAsState(initial = DeviceRecord())
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(r.keyKind) { invalid = keyInvalidated() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Phone settings", style = MaterialTheme.typography.headlineSmall)
            Line("Server", r.origin ?: "not set")
            Line("Paired", if (r.paired) "yes (device ${r.deviceId})" else "no — open Account → Devices in the app and tap Pair this phone")
            if (r.paired) {
                Line(
                    "Step-up key",
                    when (KeyKind.fromWire(r.keyKind)) {
                        KeyKind.BIOMETRIC -> "biometric, every use (${r.keyLevel})"
                        KeyKind.BIOMETRIC_OR_CREDENTIAL -> "biometric or PIN, every use (${r.keyLevel})"
                        null -> "none: read-only"
                    },
                )
                r.keyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (invalid) {
                    Text("Re-confirm this phone: its step-up key is gone (a new fingerprint or face was enrolled).", style = MaterialTheme.typography.bodySmall)
                }
                Line("Push", r.pushStatus.name.lowercase().replace('_', ' '))
                r.pushNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Line("Notifications", if (notificationsEnabled()) "allowed" else "off")
            OutlinedButton(onClick = onNotificationSettings) { Text("Notification settings") }
            if (r.paired) {
                OutlinedButton(onClick = onRetryPush) { Text("Register push again") }
                OutlinedButton(onClick = onUnpair) { Text("Unpair this phone") }
            } else if (r.origin != null) {
                OutlinedButton(onClick = onChangeServer) { Text("Change server") }
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
