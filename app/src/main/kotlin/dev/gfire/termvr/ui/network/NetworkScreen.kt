package dev.gfire.termvr.ui.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.network.QrCodeEncoder

/**
 * Renders Inbound Ports, LAN QR codes, Outbound Proxy controls, and WireGuard mode selectors.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selectedProfile = state.selectedProfile

    var hostPortText by remember { mutableStateOf("8080") }
    var guestPortText by remember { mutableStateOf("8080") }
    var selectedHost by remember { mutableStateOf(ListenHost.LOOPBACK_V4) }
    var selectedProto by remember { mutableStateOf(ForwardProtocol.TCP) }
    var pendingLanRule by remember { mutableStateOf<PendingLanRule?>(null) }

    val wgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: return@rememberLauncherForActivityResult
                val name = "wg-${System.currentTimeMillis()}"
                viewModel.importWireGuardConfig(name, content)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Network & Ports", style = MaterialTheme.typography.headlineSmall)
            if (state.profiles.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    for (profile in state.profiles) {
                        FilterChip(
                            selected = profile.id == selectedProfile?.id,
                            onClick = { viewModel.selectProfile(profile.id) },
                            label = { Text(profile.displayName) },
                        )
                    }
                }
            }
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.errorMessage != null) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(banner, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::clearBanners) { Text("Dismiss") }
                    }
                }
            }
        }

        if (selectedProfile == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Create or launch a sandbox profile on Home before configuring network rules.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            return@LazyColumn
        }

        if (selectedProfile.id in state.lanReconfirmationProfiles) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Wi-Fi Network Changed: Re-confirm LAN Exposure",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Active Wi-Fi IP: ${state.activeWifiIpv4 ?: "Unavailable"}. Confirm LAN port exposure or downgrade rules to 127.0.0.1.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.reconfirmLanExposure(selectedProfile.id) }) {
                                Text("Re-confirm LAN")
                            }
                            OutlinedButton(onClick = { viewModel.downgradeLanRulesToLoopback(selectedProfile.id) }) {
                                Text("Downgrade to 127.0.0.1")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Inbound Port Forwards", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostPortText,
                            onValueChange = { hostPortText = it },
                            label = { Text("Host Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = guestPortText,
                            onValueChange = { guestPortText = it },
                            label = { Text("Guest Port") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ListenHost.entries.forEach { lh ->
                            FilterChip(
                                selected = selectedHost == lh,
                                onClick = { selectedHost = lh },
                                label = { Text(lh.wireLiteral) },
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ForwardProtocol.entries.forEach { proto ->
                            FilterChip(
                                selected = selectedProto == proto,
                                onClick = { selectedProto = proto },
                                label = { Text(proto.name) },
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val hp = hostPortText.toIntOrNull() ?: 0
                            val gp = guestPortText.toIntOrNull() ?: 0
                            if (selectedHost.exposesLan &&
                                !PortForwardRule.isLanDockerPortBlocked(selectedHost, hp, hp, gp, gp)
                            ) {
                                pendingLanRule = PendingLanRule(selectedProto, selectedHost, hp, gp)
                            } else {
                                viewModel.addPortRule(selectedProto, selectedHost, hp, gp)
                            }
                        },
                        enabled = !state.busy,
                    ) {
                        Text("Add Port Rule")
                    }

                    if (selectedProfile.portForwards.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    for (rule in selectedProfile.portForwards) {
                        val effectiveIp = if (rule.exposesLan) {
                            state.activeWifiIpv4 ?: "127.0.0.1"
                        } else {
                            "127.0.0.1"
                        }
                        val endpointUrl = "http://$effectiveIp:${rule.hostPortStart}"
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${rule.protocol.name} ${rule.listenHost.wireLiteral}:${rule.hostPortStart} to guest :${rule.guestPortStart}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.removePortRule(rule.id) }) {
                                    Text("Remove")
                                }
                            }
                            if (rule.protocol == ForwardProtocol.TCP) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    QrCodeCanvas(
                                        content = endpointUrl,
                                        modifier = Modifier.size(92.dp),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = endpointUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = { viewModel.openUrlInCustomTab(endpointUrl) }) {
                                                Text("Open")
                                            }
                                            OutlinedButton(onClick = {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("TermVR Endpoint", endpointUrl))
                                            }) {
                                                Text("Copy")
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

        item {
            OutboundProxyCard(
                profileProxy = selectedProfile.network.outboundProxy,
                restartRequired = state.transparentTcpRestartRequired,
                testingProxy = state.testingProxy,
                lastProxyTest = state.lastProxyTest,
                onSaveProxy = viewModel::saveOutboundProxy,
                onTestProxy = viewModel::testProxyConfig,
                onRestartNetRaw = viewModel::restartForNetRawChange,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Network Backend & WireGuard", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetBackend.entries.forEach { backend ->
                            FilterChip(
                                selected = selectedProfile.network.backend == backend,
                                onClick = {
                                    viewModel.updateNetworkBackend(
                                        backend = backend,
                                        isolateLanEgress = selectedProfile.network.isolateLanEgress,
                                        wireGuardName = selectedProfile.network.wireGuardConfigName ?: state.wireGuardConfigs.firstOrNull(),
                                    )
                                },
                                label = { Text("-net=${backend.name.lowercase()}") },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedProfile.network.isolateLanEgress,
                            onCheckedChange = { checked ->
                                viewModel.updateNetworkBackend(
                                    backend = selectedProfile.network.backend,
                                    isolateLanEgress = checked,
                                    wireGuardName = selectedProfile.network.wireGuardConfigName,
                                )
                            },
                        )
                        Text("Block outbound LAN egress (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)")
                    }
                    OutlinedButton(onClick = { wgLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import WireGuard .conf")
                    }
                    if (state.wireGuardConfigs.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (wgName in state.wireGuardConfigs) {
                                FilterChip(
                                    selected = selectedProfile.network.wireGuardConfigName == wgName,
                                    onClick = {
                                        viewModel.updateNetworkBackend(
                                            backend = NetBackend.WIREGUARD,
                                            isolateLanEgress = selectedProfile.network.isolateLanEgress,
                                            wireGuardName = wgName,
                                        )
                                    },
                                    label = { Text(wgName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val confirmLan = pendingLanRule
    if (confirmLan != null) {
        AlertDialog(
            onDismissRequest = { pendingLanRule = null },
            title = { Text("Expose Port on LAN?") },
            text = {
                Text(
                    "Binding ${confirmLan.host.wireLiteral}:${confirmLan.hostPort} exposes guest port ${confirmLan.guestPort} on your local network " +
                        "(active Wi-Fi IP: ${state.activeWifiIpv4 ?: "Unavailable"}).",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.addPortRule(confirmLan.protocol, confirmLan.host, confirmLan.hostPort, confirmLan.guestPort)
                    pendingLanRule = null
                }) {
                    Text("Expose on LAN")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanRule = null }) { Text("Cancel") }
            },
        )
    }

    if (state.stagedPortApply != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissStagedPortApply,
            title = { Text("Apply Port Changes to Running Sandbox?") },
            text = {
                Text("gvx locks host port bindings at boot. Choose Checkpoint & Restore to preserve memory state, or Cold Restart.")
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmStagedPortApply(checkpointAndRestore = true) }) {
                    Text("Checkpoint & Restore")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = viewModel::dismissStagedPortApply) { Text("Later") }
                    OutlinedButton(onClick = { viewModel.confirmStagedPortApply(checkpointAndRestore = false) }) {
                        Text("Cold Restart")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutboundProxyCard(
    profileProxy: OutboundProxyConfig,
    restartRequired: Boolean,
    testingProxy: Boolean,
    lastProxyTest: dev.gfire.termvr.core.runtime.ProxyTestResult?,
    onSaveProxy: (OutboundProxyMode, ProxyProtocol, String, Int, String, String, Boolean, Boolean, List<String>) -> Unit,
    onTestProxy: (OutboundProxyConfig) -> Unit,
    onRestartNetRaw: () -> Unit,
) {
    var mode by remember(profileProxy) { mutableStateOf(profileProxy.mode) }
    var protocol by remember(profileProxy) { mutableStateOf(profileProxy.customEndpoint?.protocol ?: ProxyProtocol.HTTP) }
    var host by remember(profileProxy) { mutableStateOf(profileProxy.customEndpoint?.host ?: "127.0.0.1") }
    var portText by remember(profileProxy) { mutableStateOf((profileProxy.customEndpoint?.port ?: 8080).toString()) }
    var username by remember(profileProxy) { mutableStateOf(profileProxy.customEndpoint?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var routeDns by remember(profileProxy) { mutableStateOf(profileProxy.routeDnsViaProxy) }
    var transparentTcp by remember(profileProxy) { mutableStateOf(profileProxy.transparentTcp) }
    var noProxyEntries by remember(profileProxy) { mutableStateOf(profileProxy.noProxyEntries) }
    var newNoProxy by remember { mutableStateOf("") }

    fun buildDraftConfig(): OutboundProxyConfig {
        val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 8080
        val endpoint = if (mode == OutboundProxyMode.CUSTOM) {
            ProxyEndpointSpec(
                protocol = protocol,
                host = host.trim().ifEmpty { "127.0.0.1" },
                port = port,
                username = username.trim().ifEmpty { null },
                credentialKeyAlias = profileProxy.customEndpoint?.credentialKeyAlias,
            )
        } else {
            null
        }
        return OutboundProxyConfig(
            mode = mode,
            customEndpoint = endpoint,
            routeDnsViaProxy = routeDns,
            transparentTcp = transparentTcp,
            noProxyEntries = noProxyEntries,
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Outbound Proxy", style = MaterialTheme.typography.titleMedium)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutboundProxyMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.name) },
                    )
                }
            }

            if (mode == OutboundProxyMode.CUSTOM) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProxyProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                            label = { Text(proto.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Proxy Host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (Keystore)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text("NO_PROXY Exceptions", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                noProxyEntries.forEach { entry ->
                    AssistChip(
                        onClick = { noProxyEntries = noProxyEntries - entry },
                        label = { Text("$entry x") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newNoProxy,
                    onValueChange = { newNoProxy = it },
                    label = { Text("Add host or CIDR") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    val trimmed = newNoProxy.trim()
                    if (trimmed.isNotEmpty() && trimmed !in noProxyEntries) {
                        noProxyEntries = noProxyEntries + trimmed
                        newNoProxy = ""
                    }
                }) {
                    Text("Add")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = routeDns, onCheckedChange = { routeDns = it })
                Text("Route DNS via Proxy")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = transparentTcp, onCheckedChange = { transparentTcp = it })
                Text("Transparent TCP Redirect (--net-raw)")
            }

            if (restartRequired) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Restart required to update --net-raw mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onRestartNetRaw) { Text("Restart") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onSaveProxy(
                        mode,
                        protocol,
                        host,
                        portText.toIntOrNull() ?: 8080,
                        username,
                        password,
                        routeDns,
                        transparentTcp,
                        noProxyEntries,
                    )
                }) {
                    Text("Apply Live")
                }
                OutlinedButton(
                    onClick = { onTestProxy(buildDraftConfig()) },
                    enabled = !testingProxy,
                ) {
                    Text(if (testingProxy) "Testing..." else "Test Proxy")
                }
            }

            if (lastProxyTest != null) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (lastProxyTest.success) {
                                "${lastProxyTest.latencyMillis} ms: ${lastProxyTest.detail}"
                            } else {
                                "Failed (${lastProxyTest.latencyMillis} ms): ${lastProxyTest.detail}"
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun QrCodeCanvas(
    content: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { QrCodeEncoder.encode(content) }
    Canvas(
        modifier = modifier
            .background(Color.White)
            .padding(4.dp),
    ) {
        val count = matrix.size
        val cellSize = size.minDimension / count.toFloat()
        for (r in 0 until count) {
            for (c in 0 until count) {
                if (matrix.isDark(r, c)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(c * cellSize, r * cellSize),
                        size = Size(cellSize, cellSize),
                    )
                }
            }
        }
    }
}

private data class PendingLanRule(
    val protocol: ForwardProtocol,
    val host: ListenHost,
    val hostPort: Int,
    val guestPort: Int,
)
