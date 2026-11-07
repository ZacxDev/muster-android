package dev.zacx.muster.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.zacx.muster.core.auth.DeviceRecord
import dev.zacx.muster.core.auth.KeyKind
import dev.zacx.muster.core.auth.PushStatus
import dev.zacx.muster.core.auth.TenantRecord
import dev.zacx.muster.core.auth.Tenants
import kotlinx.coroutines.flow.Flow

/**
 * First run, and Add server: a muster server's URL. The repo carries no
 * deployment's address. [onSubmit] answers null when the server was taken,
 * or the message to show. [onCancel] (Add server only) goes back.
 */
@Composable
fun ServerUrlScreen(adding: Boolean = false, onCancel: (() -> Unit)? = null, onSubmit: (String) -> String?) {
    var url by remember { mutableStateOf("https://") }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (adding) "Add a muster server" else "muster", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (adding) {
                    "Enter the other muster server's address. Sign in there, then open Account → Devices and tap Pair this phone: " +
                        "it gets its own pairing, and its notifications arrive alongside this phone's other servers."
                } else {
                    "Enter your muster server's address. The app opens only that address; other links open in your browser."
                },
            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { error = onSubmit(url) }, modifier = Modifier.testTag(TAG_URL_CONTINUE)) { Text("Continue") }
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
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

/** The native phone settings for one muster server ([title]): what the phone can do there, and why not. */
@Composable
fun PhoneSettingsScreen(
    title: String,
    records: Flow<DeviceRecord>,
    keyInvalidated: suspend () -> Boolean,
    notificationsEnabled: () -> Boolean,
    onNotificationSettings: () -> Unit,
    onRetryPush: () -> Unit,
    onUnpair: () -> Unit,
    onRemoveServer: () -> Unit,
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
            Text("Phone settings: $title", style = MaterialTheme.typography.headlineSmall)
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
            }
            OutlinedButton(onClick = onRemoveServer) { Text("Remove this server") }
            TextButton(onClick = onClose) { Text("Back") }
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

const val TAG_PICKER = "tenant-picker"
const val TAG_ADD = "tenant-add"
const val TAG_MANAGE = "tenant-manage"
const val TAG_URL_CONTINUE = "url-continue"
const val TAG_REMOVE_CONFIRM = "remove-confirm"

/** The menu row for tenant [id]. */
fun tagTenant(id: String) = "tenant-$id"

/**
 * The switcher (muster docs/design/multi-tenant.md, "Switcher UX"): a native
 * bar above the page showing the server in view, its colour and label. A tap
 * opens the menu of servers, then Add server and Manage servers; picking
 * another server switches to it. A long press opens server management.
 * It renders from local state only: a slow server never blocks it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TenantBar(tenants: Tenants, onSelect: (String) -> Unit, onManage: () -> Unit, onAdd: () -> Unit) {
    val active = tenants.active ?: return
    var open by remember { mutableStateOf(false) }
    Surface(color = Color(SHELL_BAR), contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(
                Modifier
                    .testTag(TAG_PICKER)
                    .combinedClickable(
                        onClickLabel = "Switch muster server",
                        onLongClickLabel = "Manage muster servers",
                        onClick = { open = true },
                        onLongClick = onManage,
                    )
                    .semantics { contentDescription = "Server: ${active.label}" }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColourDot(active.colour)
                Text(active.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▾", style = MaterialTheme.typography.titleSmall)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                tenants.all.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label + if (t.device.paired) "" else " (not paired)") },
                        leadingIcon = { ColourDot(t.colour) },
                        trailingIcon = if (t.id == active.id) ({ Text("✓") }) else null,
                        onClick = {
                            open = false
                            if (t.id != active.id) onSelect(t.id)
                        },
                        modifier = Modifier.testTag(tagTenant(t.id)),
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Add server") }, onClick = { open = false; onAdd() }, modifier = Modifier.testTag(TAG_ADD))
                DropdownMenuItem(text = { Text("Manage servers") }, onClick = { open = false; onManage() }, modifier = Modifier.testTag(TAG_MANAGE))
            }
        }
    }
}

@Composable
private fun ColourDot(colour: Int) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(colour)))
}

/**
 * Server management: every muster server this phone holds, which is in view,
 * whether it is paired. Open switches to one; Settings shows its phone
 * settings; Remove ends its pairing and forgets it (after a confirmation).
 */
@Composable
fun ServersScreen(
    tenants: Tenants,
    onOpen: (String) -> Unit,
    onSettings: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onDismissNotice: () -> Unit,
    onClose: (() -> Unit)?,
) {
    var confirm by remember { mutableStateOf<TenantRecord?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("muster servers", style = MaterialTheme.typography.headlineSmall)
            tenants.notice?.let { n ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDismissNotice) { Text("OK") }
                    }
                }
            }
            tenants.all.forEach { t ->
                Column(Modifier.fillMaxWidth().testTag("server-${t.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColourDot(t.colour)
                        Text(t.label + if (t.id == tenants.active?.id) " (in view)" else "", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(t.origin, style = MaterialTheme.typography.bodySmall)
                    Text(if (t.device.paired) "Paired" else "Not paired: sign in, then Account → Devices → Pair this phone", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onOpen(t.id) }) { Text("Open") }
                        TextButton(onClick = { onSettings(t.id) }) { Text("Settings") }
                        TextButton(onClick = { confirm = t }, modifier = Modifier.testTag("remove-${t.id}")) { Text("Remove") }
                    }
                }
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.testTag(TAG_ADD)) { Text("Add server") }
            if (onClose != null) TextButton(onClick = onClose) { Text("Close") }
        }
    }
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove ${t.label}?") },
            text = {
                Text(
                    "This phone stops getting its notifications. Its pairing is ended on that server, and its sign-in, " +
                        "step-up key and settings are deleted from this phone. Other servers are not touched.",
                )
            },
            confirmButton = { TextButton(onClick = { confirm = null; onRemove(t.id) }, modifier = Modifier.testTag(TAG_REMOVE_CONFIRM)) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** The switcher bar's background: a shade above muster's dark theme-color (MainActivity.SHELL_BACKGROUND). */
private const val SHELL_BAR = 0xFF1B1830
