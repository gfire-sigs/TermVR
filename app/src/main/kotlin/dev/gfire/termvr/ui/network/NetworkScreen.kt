package dev.gfire.termvr.ui.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import dev.gfire.termvr.ui.theme.LocalTermVrMonoFontFamily
import dev.gfire.termvr.ui.theme.TermVrBadge
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrCard
import dev.gfire.termvr.ui.theme.TermVrControlShape
import dev.gfire.termvr.ui.theme.TermVrIcons
import dev.gfire.termvr.ui.theme.TermVrNoticeBanner
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrSectionHeader
import dev.gfire.termvr.ui.theme.TermVrSelectableChip
import dev.gfire.termvr.ui.theme.TermVrTextButton
import dev.gfire.termvr.ui.theme.runStateColor

private enum class NetworkSubTab(val label: String) {
    PORTS("Ports"),
    PROXY("Proxy"),
    WIREGUARD("WireGuard"),
}

/**
 * Renders container network controls across Ports, Proxy, and WireGuard sub-tabs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val monoFont = LocalTermVrMonoFontFamily.current
    val selectedProfile = state.selectedProfile

    var activeTab by remember { mutableStateOf(NetworkSubTab.PORTS) }
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
                querySafDocumentMetadata(context.contentResolver, uri)
            }.fold(
                onSuccess = { meta ->
                    val rawName = meta.displayName?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.takeIf { uri.scheme == "file" }
                    viewModel.importWireGuardConfigFromStream(
                        rawDisplayName = rawName,
                        declaredSizeBytes = meta.sizeBytes,
                        openStream = { context.contentResolver.openInputStream(uri) },
                    )
                },
                onFailure = { err ->
                    viewModel.reportWireGuardImportError(
                        err.message?.takeIf { it.isNotBlank() }
                            ?: "Unable to inspect selected WireGuard config file.",
                    )
                },
            )
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TermVrSectionHeader(title = "Network")
        }

        if (state.profiles.isNotEmpty()) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (profile in state.profiles) {
                        val runState = state.runStates[profile.id]
                        TermVrSelectableChip(
                            label = profile.displayName,
                            selected = profile.id == selectedProfile?.id,
                            onClick = { viewModel.selectProfile(profile.id) },
                            leadingDotColor = runState?.let { runStateColor(it) },
                        )
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetworkSubTab.entries.forEach { tab ->
                    TermVrSelectableChip(
                        label = tab.label,
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                    )
                }
            }
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            item {
                TermVrNoticeBanner(
                    message = banner,
                    isError = state.errorMessage != null,
                    onDismiss = viewModel::clearBanners,
                )
            }
        }

        if (selectedProfile == null) {
            item {
                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Create or start a container before configuring network rules.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@LazyColumn
        }

        if (selectedProfile.id in state.lanReconfirmationProfiles) {
            item {
                TermVrCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Wi-Fi Changed: Confirm LAN Exposure",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = "Wi-Fi IP: ${state.activeWifiIpv4 ?: "Unavailable"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = monoFont,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TermVrButton(onClick = { viewModel.reconfirmLanExposure(selectedProfile.id) }) {
                                Text("Confirm LAN")
                            }
                            TermVrOutlinedButton(
                                onClick = { viewModel.downgradeLanRulesToLoopback(selectedProfile.id) },
                                borderColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Text("Use 127.0.0.1")
                            }
                        }
                    }
                }
            }
        }

        when (activeTab) {
            NetworkSubTab.PORTS -> {
                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Port Forwarding", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = hostPortText,
                                    onValueChange = { hostPortText = it },
                                    label = { Text("Host Port") },
                                    singleLine = true,
                                    shape = TermVrControlShape,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = guestPortText,
                                    onValueChange = { guestPortText = it },
                                    label = { Text("Guest Port") },
                                    singleLine = true,
                                    shape = TermVrControlShape,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ListenHost.entries.forEach { lh ->
                                    TermVrSelectableChip(
                                        label = lh.wireLiteral,
                                        selected = selectedHost == lh,
                                        onClick = { selectedHost = lh },
                                    )
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ForwardProtocol.entries.forEach { proto ->
                                    TermVrSelectableChip(
                                        label = proto.name,
                                        selected = selectedProto == proto,
                                        onClick = { selectedProto = proto },
                                    )
                                }
                            }

                            TermVrButton(
                                onClick = {
                                    val hp = hostPortText.toIntOrNull() ?: 0
                                    val gp = guestPortText.toIntOrNull() ?: 0
                                    if (selectedHost.exposesLan &&
                                        viewModel.validateCandidatePortRule(selectedProto, selectedHost, hp, gp)
                                    ) {
                                        pendingLanRule = PendingLanRule(selectedProto, selectedHost, hp, gp)
                                    } else {
                                        viewModel.addPortRule(selectedProto, selectedHost, hp, gp)
                                    }
                                },
                                enabled = !state.busy,
                            ) {
                                Icon(
                                    imageVector = TermVrIcons.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text("Add Rule", modifier = Modifier.padding(start = 6.dp))
                            }

                            if (selectedProfile.portForwards.isNotEmpty()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }

                            val profilePublishedPorts = state.publishedPorts[selectedProfile.id].orEmpty()
                            for (rule in selectedProfile.portForwards) {
                                val effectiveIp = if (rule.exposesLan) {
                                    state.activeWifiIpv4 ?: "127.0.0.1"
                                } else {
                                    "127.0.0.1"
                                }
                                val effectiveHostPort = resolveEffectiveHostPort(rule, profilePublishedPorts)
                                val hostPortLabel = effectiveHostPort?.toString() ?: "pending"
                                val endpointUrl = effectiveHostPort?.let { "http://$effectiveIp:$it" }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${rule.protocol.name} ${rule.listenHost.wireLiteral}:$hostPortLabel -> :${rule.guestPortStart}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = monoFont,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { viewModel.removePortRule(rule.id) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = TermVrIcons.Delete,
                                                contentDescription = "Remove rule",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    if (rule.protocol == ForwardProtocol.TCP) {
                                        if (endpointUrl != null) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                QrCodeCanvas(
                                                    content = endpointUrl,
                                                    modifier = Modifier
                                                        .size(88.dp)
                                                        .clip(TermVrControlShape)
                                                        .border(1.dp, MaterialTheme.colorScheme.outline, TermVrControlShape),
                                                )
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(
                                                        text = endpointUrl,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontFamily = monoFont,
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        TermVrOutlinedButton(
                                                            onClick = { viewModel.openUrlInCustomTab(endpointUrl) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                        ) {
                                                            Text("Open", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                        TermVrOutlinedButton(
                                                            onClick = {
                                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                cm.setPrimaryClip(ClipData.newPlainText("TermVR Endpoint", endpointUrl))
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                        ) {
                                                            Text("Copy", style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Text(
                                                text = "Port assigns when container starts.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            NetworkSubTab.PROXY -> {
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
            }

            NetworkSubTab.WIREGUARD -> {
                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Backend & WireGuard", style = MaterialTheme.typography.titleMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                NetBackend.entries.forEach { backend ->
                                    TermVrSelectableChip(
                                        label = backend.name.lowercase(),
                                        selected = selectedProfile.network.backend == backend,
                                        onClick = {
                                            viewModel.updateNetworkBackend(
                                                backend = backend,
                                                isolateLanEgress = selectedProfile.network.isolateLanEgress,
                                                wireGuardName = selectedProfile.network.wireGuardConfigName ?: state.wireGuardConfigs.firstOrNull(),
                                            )
                                        },
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
                                Text(
                                    text = "Block private LAN egress",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            TermVrOutlinedButton(onClick = { wgLauncher.launch(arrayOf("*/*")) }) {
                                Text("Import .conf")
                            }
                            if (state.wireGuardConfigs.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    for (wgName in state.wireGuardConfigs) {
                                        TermVrSelectableChip(
                                            label = wgName,
                                            selected = selectedProfile.network.wireGuardConfigName == wgName,
                                            onClick = {
                                                viewModel.updateNetworkBackend(
                                                    backend = NetBackend.WIREGUARD,
                                                    isolateLanEgress = selectedProfile.network.isolateLanEgress,
                                                    wireGuardName = wgName,
                                                )
                                            },
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

    val confirmLan = pendingLanRule
    if (confirmLan != null) {
        AlertDialog(
            onDismissRequest = { pendingLanRule = null },
            title = { Text("Expose Port on LAN?") },
            text = {
                Text(
                    "Binding ${confirmLan.host.wireLiteral}:${confirmLan.hostPort} exposes guest port ${confirmLan.guestPort} on Wi-Fi (${state.activeWifiIpv4 ?: "Unavailable"}).",
                )
            },
            confirmButton = {
                TermVrButton(onClick = {
                    viewModel.addPortRule(confirmLan.protocol, confirmLan.host, confirmLan.hostPort, confirmLan.guestPort)
                    pendingLanRule = null
                }) {
                    Text("Expose")
                }
            },
            dismissButton = {
                TermVrTextButton(onClick = { pendingLanRule = null }) { Text("Cancel") }
            },
        )
    }

    if (state.stagedPortApply != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissStagedPortApply,
            title = { Text("Apply Port Changes?") },
            text = {
                Text("Choose Checkpoint & Restore to keep open shells active, or Cold Restart to reboot cleanly.")
            },
            confirmButton = {
                TermVrButton(onClick = { viewModel.confirmStagedPortApply(checkpointAndRestore = true) }) {
                    Text("Checkpoint & Restore")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermVrTextButton(onClick = viewModel::dismissStagedPortApply) { Text("Later") }
                    TermVrOutlinedButton(onClick = { viewModel.confirmStagedPortApply(checkpointAndRestore = false) }) {
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
    val monoFont = LocalTermVrMonoFontFamily.current
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

    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Outbound Proxy", style = MaterialTheme.typography.titleMedium)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutboundProxyMode.entries.forEach { m ->
                    TermVrSelectableChip(
                        label = m.name,
                        selected = mode == m,
                        onClick = { mode = m },
                    )
                }
            }

            if (mode == OutboundProxyMode.CUSTOM) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProxyProtocol.entries.forEach { proto ->
                        TermVrSelectableChip(
                            label = proto.name,
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        shape = TermVrControlShape,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        shape = TermVrControlShape,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        shape = TermVrControlShape,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        shape = TermVrControlShape,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text("Bypass Hosts (NO_PROXY)", style = MaterialTheme.typography.labelLarge)
            if (noProxyEntries.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    noProxyEntries.forEach { entry ->
                        TermVrSelectableChip(
                            label = "$entry ×",
                            selected = false,
                            onClick = { noProxyEntries = noProxyEntries - entry },
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newNoProxy,
                    onValueChange = { newNoProxy = it },
                    label = { Text("Host or CIDR") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.weight(1f),
                )
                TermVrOutlinedButton(onClick = {
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
                Text("Route DNS through proxy", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = transparentTcp, onCheckedChange = { transparentTcp = it })
                Text("Transparent TCP redirect", style = MaterialTheme.typography.bodyMedium)
            }

            if (restartRequired) {
                TermVrNoticeBanner(
                    message = "Restart container to update TCP redirect mode.",
                    isError = true,
                    actionLabel = "Restart",
                    onAction = onRestartNetRaw,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermVrButton(onClick = {
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
                    Text("Apply")
                }
                TermVrOutlinedButton(
                    onClick = { onTestProxy(buildDraftConfig()) },
                    enabled = !testingProxy,
                ) {
                    Text(if (testingProxy) "Testing..." else "Test")
                }
            }

            if (lastProxyTest != null) {
                TermVrBadge(
                    text = if (lastProxyTest.success) {
                        "${lastProxyTest.latencyMillis} ms · ${lastProxyTest.detail}"
                    } else {
                        "Failed (${lastProxyTest.latencyMillis} ms) · ${lastProxyTest.detail}"
                    },
                    containerColor = if (lastProxyTest.success) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (lastProxyTest.success) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
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

internal data class SafDocumentMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

internal fun querySafDocumentMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
): SafDocumentMetadata {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        extractSafDocumentMetadata(cursor)
    } ?: SafDocumentMetadata(displayName = null, sizeBytes = null)
}

internal fun extractSafDocumentMetadata(cursor: Cursor): SafDocumentMetadata {
    if (!cursor.moveToFirst()) {
        return SafDocumentMetadata(displayName = null, sizeBytes = null)
    }
    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val displayName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
        cursor.getString(nameIdx)
    } else {
        null
    }
    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
    val sizeBytes = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
        cursor.getLong(sizeIdx).takeIf { it >= 0L }
    } else {
        null
    }
    return SafDocumentMetadata(displayName = displayName, sizeBytes = sizeBytes)
}
