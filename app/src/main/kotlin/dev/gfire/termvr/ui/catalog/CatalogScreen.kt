package dev.gfire.termvr.ui.catalog

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import dev.gfire.termvr.ui.theme.LocalTermVrMonoFontFamily
import dev.gfire.termvr.ui.theme.TermVrBadge
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrControlShape
import dev.gfire.termvr.ui.theme.TermVrNoticeBanner
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrRunStateDot
import dev.gfire.termvr.ui.theme.TermVrCard
import dev.gfire.termvr.ui.theme.TermVrTextButton
import dev.gfire.termvr.ui.theme.runStateLabel
import dev.gfire.termvr.ui.theme.shortImageRef
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
    var imageRefInput by remember { mutableStateOf("docker.io/library/debian:trixie-slim") }
    var showRegistryDialog by remember { mutableStateOf(false) }
    var isOnline by remember { mutableStateOf(isNetworkConnected(context)) }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = isNetworkConnected(context)
            }

            override fun onLost(network: Network) {
                isOnline = isNetworkConnected(context)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                isOnline = isNetworkConnected(context)
            }
        }
        val registered = runCatching {
            cm?.registerDefaultNetworkCallback(callback)
            cm != null
        }.getOrDefault(false)
        onDispose {
            if (registered) {
                runCatching { cm?.unregisterNetworkCallback(callback) }
            }
        }
    }

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
        TabRow(
            selectedTabIndex = state.selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline) },
        ) {
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
                text = { Text("Images") },
            )
        }

        if (state.busyAction != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = state.busyAction.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LocalTermVrMonoFontFamily.current,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        val bannerText = state.errorMessage ?: state.statusMessage
        if (bannerText != null) {
            TermVrNoticeBanner(
                message = bannerText,
                isError = state.errorMessage != null,
                onDismiss = viewModel::clearBanner,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.selectedTab) {
                CatalogSubTab.DISTRIBUTIONS -> {
                    items(state.distributions, key = { it.id }) { dist ->
                        DistributionCard(
                            entry = dist,
                            busy = state.busyAction != null,
                            onPullAndLaunch = {
                                viewModel.createAndLaunchDistribution(dist, pullFirst = true, onOpenTerminal)
                            },
                            onLaunch = {
                                viewModel.createAndLaunchDistribution(dist, pullFirst = false, onOpenTerminal)
                            },
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
                        LocalImagesCard(
                            imageRefInput = imageRefInput,
                            onImageRefChange = { imageRefInput = it },
                            busy = state.busyAction != null,
                            isOnline = isOnline,
                            onPull = { viewModel.pullImage(imageRefInput) },
                            onOpenRegistryAuth = { showRegistryDialog = true },
                            onLoadTar = {
                                loadTarLauncher.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*"))
                            },
                            onExportTar = { saveTarLauncher.launch("image-export.tar") },
                        )
                    }

                    item {
                        DiskSummaryCard(
                            state = state,
                            onRefresh = viewModel::refreshStorageStats,
                            onPrune = viewModel::pruneUnused,
                            onInspectVolume = viewModel::inspectVolume,
                        )
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

@Composable
private fun DistributionCard(
    entry: DistributionCatalogEntry,
    busy: Boolean,
    onPullAndLaunch: () -> Unit,
    onLaunch: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val isDefault = entry.id == "debian-trixie"
    val shortTag = when (entry.distroSlug) {
        "debian", "ubuntu" -> "glibc · apt"
        "fedora" -> "glibc · dnf"
        "alpine" -> "musl · apk"
        else -> entry.architecture.ociArch
    }

    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = entry.displayName, style = MaterialTheme.typography.titleMedium)
                        if (isDefault) {
                            TermVrBadge(
                                text = "Default",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            )
                        }
                    }
                    Text(
                        text = shortImageRef(entry.imageRef),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TermVrBadge(text = "~${entry.approximateDownloadMiB} MB")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TermVrBadge(text = shortTag)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermVrOutlinedButton(onClick = onPullAndLaunch, enabled = !busy) {
                        Text("Pull")
                    }
                    TermVrButton(onClick = onLaunch, enabled = !busy) {
                        Text("Launch")
                    }
                }
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
    val mono = LocalTermVrMonoFontFamily.current
    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = recipe.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = shortImageRef(recipe.imageRef),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TermVrRunStateDot(runState = runState, size = 7.dp)
                    TermVrBadge(text = runStateLabel(runState))
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TermVrBadge(text = "Min ${preflight.requiredRamMiB} MB RAM")
                TermVrBadge(text = "Min ${preflight.requiredDiskMiB} MB Disk")
                if (runState == SandboxRunState.RUNNING) {
                    TermVrBadge(
                        text = if (probeReady) "Port ${recipe.readinessTcpPort} ready" else "Starting :${recipe.readinessTcpPort}",
                        containerColor = if (probeReady) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (probeReady) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (recipe.optionalAssetManifest != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = calicoEnabled, onCheckedChange = { onToggleCalico() })
                    Text(
                        text = "Calico VXLAN (${recipe.optionalVariantMinProfileRamMiB ?: 4096} MB)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (!preflight.passed) {
                Text(
                    text = "Below minimum RAM or disk (${preflight.requiredRamMiB} MB RAM, ${preflight.requiredDiskMiB} MB disk).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermVrButton(
                    onClick = onLaunch,
                    enabled = !busy && preflight.diskOk,
                ) {
                    Text(if (runState == SandboxRunState.RUNNING) "Open" else "Launch")
                }
                if (recipe.id == "k3s-single-node") {
                    TermVrOutlinedButton(onClick = onExportKubeconfig, enabled = !busy) {
                        Text("kubeconfig")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalImagesCard(
    imageRefInput: String,
    onImageRefChange: (String) -> Unit,
    busy: Boolean,
    isOnline: Boolean,
    onPull: () -> Unit,
    onOpenRegistryAuth: () -> Unit,
    onLoadTar: () -> Unit,
    onExportTar: () -> Unit,
) {
    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Image Tools", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = imageRefInput,
                onValueChange = onImageRefChange,
                label = { Text("Image Reference") },
                singleLine = true,
                shape = TermVrControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TermVrButton(onClick = onPull, enabled = !busy) {
                    Text("Pull")
                }
                TermVrOutlinedButton(onClick = onOpenRegistryAuth, enabled = !busy) {
                    Text("Registry Auth")
                }
                TermVrOutlinedButton(onClick = onLoadTar, enabled = !busy) {
                    Text("Load .tar")
                }
                TermVrOutlinedButton(
                    onClick = onExportTar,
                    enabled = !busy && isOnline && imageRefInput.isNotBlank(),
                ) {
                    Text("Export .tar")
                }
            }
        }
    }
}

@Composable
private fun DiskSummaryCard(
    state: CatalogUiState,
    onRefresh: () -> Unit,
    onPrune: () -> Unit,
    onInspectVolume: (String) -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Image Store", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TermVrTextButton(onClick = onRefresh) { Text("Refresh") }
                    TermVrTextButton(onClick = onPrune) { Text("Prune") }
                }
            }
            for (df in state.dfEntries) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${df.type} (${df.totalCount})",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                    )
                    Text(
                        text = df.size,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (vol in state.volumeEntries) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = vol.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        modifier = Modifier.weight(1f),
                    )
                    TermVrOutlinedButton(
                        onClick = { onInspectVolume(vol.name) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Inspect", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            for (detail in state.inspectedVolume) {
                Text(
                    text = "${detail.name}: ${detail.mountpoint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        shape = TermVrControlShape,
        title = { Text("Registry Login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Registry Host") },
                    singleLine = true,
                    shape = TermVrControlShape,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    shape = TermVrControlShape,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Token or Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = TermVrControlShape,
                )
            }
        },
        confirmButton = {
            TermVrButton(onClick = { onAuthenticate(host, username, password) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
