package dev.gfire.termvr.ui.catalog

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.RecipeManifest
import dev.gfire.termvr.core.model.SandboxRunState
import java.io.File

/**
 * Renders the Catalog browser for curated Linux distributions, recipes, and local OCI images.
 */
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var imageRefInput by remember { mutableStateOf("alpine:3.21") }
    var showRegistryDialog by remember { mutableStateOf(false) }

    val loadTarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val tempTar = File(context.cacheDir, "import-image-${System.currentTimeMillis()}.tar")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempTar.outputStream().use { output -> input.copyTo(output) }
                }
                viewModel.loadImageTar(tempTar)
            }
        }
    }

    val saveTarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-tar"),
    ) { targetUri ->
        if (targetUri != null) {
            val tempTar = File(context.cacheDir, "export-image-${System.currentTimeMillis()}.tar")
            val online = isNetworkConnected(context)
            viewModel.exportImageTar(
                imageRef = imageRefInput,
                outputTarFile = tempTar,
                isOnline = online,
                onArchiveReady = { file ->
                    runCatching {
                        context.contentResolver.openOutputStream(targetUri)?.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        file.delete()
                    }
                },
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == CatalogSubTab.DISTRIBUTIONS,
                onClick = { viewModel.selectTab(CatalogSubTab.DISTRIBUTIONS) },
                text = { Text("Distributions") },
            )
            Tab(
                selected = state.selectedTab == CatalogSubTab.RECIPES,
                onClick = { viewModel.selectTab(CatalogSubTab.RECIPES) },
                text = { Text("Recipes") },
            )
            Tab(
                selected = state.selectedTab == CatalogSubTab.LOCAL_IMAGES,
                onClick = { viewModel.selectTab(CatalogSubTab.LOCAL_IMAGES) },
                text = { Text("Local Images") },
            )
        }

        if (state.busyAction != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = state.busyAction.orEmpty(), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }

        val bannerText = state.errorMessage ?: state.statusMessage
        if (bannerText != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
                    Text(text = bannerText, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::clearBanner) { Text("Dismiss") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.selectedTab) {
                CatalogSubTab.DISTRIBUTIONS -> {
                    items(state.distributions, key = { it.id }) { dist ->
                        DistributionCard(
                            entry = dist,
                            busy = state.busyAction != null,
                            onPullAndLaunch = { viewModel.createAndLaunchDistribution(dist, pullFirst = true, onOpenTerminal) },
                            onLaunch = { viewModel.createAndLaunchDistribution(dist, pullFirst = false, onOpenTerminal) },
                        )
                    }
                }

                CatalogSubTab.RECIPES -> {
                    items(state.recipes, key = { it.id }) { recipe ->
                        val preflight = state.preflightFor(recipe)
                        val runState = state.runStates[recipe.id] ?: SandboxRunState.STOPPED
                        val probeReady = state.recipeProbeReady[recipe.id] == true
                        RecipeCard(
                            recipe = recipe,
                            preflight = preflight,
                            runState = runState,
                            probeReady = probeReady,
                            calicoEnabled = state.calicoVxlanEnabled,
                            busy = state.busyAction != null,
                            onToggleCalico = viewModel::toggleCalicoVxlan,
                            onLaunch = { viewModel.launchRecipe(recipe, onOpenTerminal) },
                            onExportKubeconfig = { viewModel.exportKubeconfig(recipe.id) },
                        )
                    }
                }

                CatalogSubTab.LOCAL_IMAGES -> {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("OCI Image Operations", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = imageRefInput,
                                    onValueChange = { imageRefInput = it },
                                    label = { Text("Image Reference") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.pullImage(imageRefInput) },
                                        enabled = state.busyAction == null,
                                    ) {
                                        Text("Pull Image")
                                    }
                                    OutlinedButton(
                                        onClick = { showRegistryDialog = true },
                                        enabled = state.busyAction == null,
                                    ) {
                                        Text("Registry Auth")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { loadTarLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*")) },
                                        enabled = state.busyAction == null,
                                    ) {
                                        Text("Load .tar")
                                    }
                                    OutlinedButton(
                                        onClick = { saveTarLauncher.launch("image-export.tar") },
                                        enabled = state.busyAction == null,
                                    ) {
                                        Text("Export .tar (Online)")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Storage & Volumes", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(onClick = viewModel::refreshStorageStats) { Text("Refresh") }
                                        TextButton(onClick = viewModel::pruneUnused) { Text("Prune") }
                                    }
                                }
                                for (df in state.dfEntries) {
                                    Text(
                                        text = "${df.type}: ${df.totalCount} total (${df.active} active), ${df.size}, reclaimable ${df.reclaimable}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                for (vol in state.volumeEntries) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${vol.name} (${vol.driver})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { viewModel.inspectVolume(vol.name) }) {
                                            Text("Inspect")
                                        }
                                    }
                                }
                                for (detail in state.inspectedVolume) {
                                    Text(
                                        text = "Inspected ${detail.name}: mount ${detail.mountpoint} (${detail.createdAt})",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRegistryDialog) {
        RegistryAuthDialog(
            onDismiss = { showRegistryDialog = false },
            onAuthenticate = { host, user, pass ->
                viewModel.authenticateRegistry(host, user, pass)
                showRegistryDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistributionCard(
    entry: DistributionCatalogEntry,
    busy: Boolean,
    onPullAndLaunch: () -> Unit,
    onLaunch: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = entry.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${entry.imageRef} (${entry.architecture.ociArch})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = entry.summary, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${entry.defaultMemoryMiB} MiB") })
                AssistChip(onClick = {}, label = { Text("~${entry.approximateDownloadMiB} MiB") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPullAndLaunch, enabled = !busy) { Text("Pull & Launch") }
                OutlinedButton(onClick = onLaunch, enabled = !busy) { Text("Launch Existing") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeCard(
    recipe: RecipeManifest,
    preflight: RecipePreflightStatus,
    runState: SandboxRunState,
    probeReady: Boolean,
    calicoEnabled: Boolean,
    busy: Boolean,
    onToggleCalico: () -> Unit,
    onLaunch: () -> Unit,
    onExportKubeconfig: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = recipe.title, style = MaterialTheme.typography.titleMedium)
                AssistChip(onClick = {}, label = { Text(runState.name) })
            }
            Text(text = recipe.summary, style = MaterialTheme.typography.bodyMedium)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("Min RAM: ${preflight.requiredRamMiB} MiB") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Min Disk: ${preflight.requiredDiskMiB} MiB") },
                )
                val port = recipe.readinessTcpPort
                if (runState == SandboxRunState.RUNNING) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(if (probeReady) "Port $port Ready" else "Probing port $port")
                        },
                    )
                }
            }

            if (recipe.optionalAssetManifest != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = calicoEnabled, onCheckedChange = { onToggleCalico() })
                    Text(
                        text = "Calico VXLAN CNI (raises RAM floor to ${recipe.optionalVariantMinProfileRamMiB ?: 4096} MiB)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (!preflight.passed) {
                Text(
                    text = "Host preflight warning: device RAM or free disk is below recipe minimum (${preflight.requiredRamMiB} MiB RAM, ${preflight.requiredDiskMiB} MiB disk).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunch,
                    enabled = !busy && preflight.diskOk,
                ) {
                    Text(if (runState == SandboxRunState.RUNNING) "Open Terminal" else "Launch Recipe")
                }
                if (recipe.id == "k3s-single-node") {
                    OutlinedButton(onClick = onExportKubeconfig, enabled = !busy) {
                        Text("Export kubeconfig")
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistryAuthDialog(
    onDismiss: () -> Unit,
    onAuthenticate: (String, String, String) -> Unit,
) {
    var host by remember { mutableStateOf("ghcr.io") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OCI Registry Auth (TLS 1.3)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Registry Host") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Token or Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAuthenticate(host, username, password) }) {
                Text("Verify & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
